import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, Validators, AbstractControl, ValidationErrors, AsyncValidatorFn } from '@angular/forms';
import { ProfileService, UserProfile } from '../../../../core/services/profile.service';
import { AuthStore } from '../../../../core/store/auth.store';
import { catchError, map, Observable, of, switchMap, timer } from 'rxjs';

@Component({
  selector: 'app-user-profile',
  templateUrl: './user-profile.component.html',
  styleUrls: ['./user-profile.component.css']
})
export class UserProfileComponent implements OnInit {
  private fb = inject(FormBuilder);
  private profileService = inject(ProfileService);
  authStore = inject(AuthStore);

  // State
  profile = signal<UserProfile | null>(null);
  loading = signal(true);
  saving = signal(false);
  changingPassword = signal(false);

  // Notifications
  successMessage = signal('');
  errorMessage = signal('');
  passwordSuccess = signal('');
  passwordError = signal('');

  // Password visibility
  showCurrentPassword = signal(false);
  showNewPassword = signal(false);
  showConfirmPassword = signal(false);

  // Active section
  activeSection = signal<'info' | 'security'>('info');

  // Forms
  profileForm!: FormGroup;
  passwordForm!: FormGroup;

  ngOnInit(): void {
    this.initForms();
    this.loadProfile();
  }

  private initForms(): void {
    this.profileForm = this.fb.group({
      firstName: ['', [Validators.required, Validators.minLength(2)]],
      lastName: ['', [Validators.required, Validators.minLength(2)]],
      phoneNumber: ['', {
        validators: [Validators.required, Validators.pattern(/^\+?[0-9]{8,15}$/)],
        asyncValidators: [this.phoneUniqueValidator()]
      }],
      address: [''],
      dateOfBirth: ['']
    });

    this.passwordForm = this.fb.group({
      currentPassword: ['', [Validators.required]],
      newPassword: ['', [Validators.required, Validators.minLength(8)]],
      confirmPassword: ['', [Validators.required]]
    }, { validators: this.passwordMatchValidator });
  }

  private passwordMatchValidator(control: AbstractControl): ValidationErrors | null {
    const newPassword = control.get('newPassword')?.value;
    const confirmPassword = control.get('confirmPassword')?.value;
    if (newPassword && confirmPassword && newPassword !== confirmPassword) {
      return { passwordMismatch: true };
    }
    return null;
  }

  loadProfile(): void {
    this.loading.set(true);
    this.profileService.getProfile().subscribe({
      next: (profile) => {
        this.profile.set(profile);
        this.profileForm.patchValue({
          firstName: profile.firstName || '',
          lastName: profile.lastName || '',
          phoneNumber: profile.phoneNumber || '',
          address: profile.address || '',
          dateOfBirth: profile.dateOfBirth || ''
        });
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Error loading profile:', err);
        this.errorMessage.set('Erreur lors du chargement du profil');
        this.loading.set(false);
      }
    });
  }

  saveProfile(): void {
    if (this.profileForm.invalid || this.profileForm.pending) return;
    this.saving.set(true);
    this.clearMessages();

    const data = this.profileForm.value;

    this.profileService.updateProfile({
      firstName: data.firstName,
      lastName: data.lastName,
      phoneNumber: data.phoneNumber || null,
      address: data.address || null,
      dateOfBirth: data.dateOfBirth || null
    }).subscribe({
      next: (updatedProfile) => {
        this.profile.set(updatedProfile);
        this.saving.set(false);
        this.successMessage.set('Profil mis à jour avec succès');
        
        // Push changes to global AuthStore immediately for sidebar/header updates
        this.authStore.updateUser({
          firstName: updatedProfile.firstName,
          lastName: updatedProfile.lastName
        });

        this.autoClearMessage('success');
      },
      error: (err) => {
        this.saving.set(false);
        this.errorMessage.set(err.error?.message || 'Erreur lors de la mise à jour du profil');
        this.autoClearMessage('error');
      }
    });
  }

  changePassword(): void {
    if (this.passwordForm.invalid) return;
    this.changingPassword.set(true);
    this.clearPasswordMessages();

    const data = this.passwordForm.value;

    this.profileService.changePassword({
      currentPassword: data.currentPassword,
      newPassword: data.newPassword
    }).subscribe({
      next: () => {
        this.changingPassword.set(false);
        this.passwordSuccess.set('Mot de passe modifié avec succès');
        this.passwordForm.reset();
        this.autoClearMessage('passwordSuccess');
      },
      error: (err) => {
        this.changingPassword.set(false);
        this.passwordError.set(err.error?.message || 'Erreur lors du changement de mot de passe');
        this.autoClearMessage('passwordError');
      }
    });
  }

  // VALIDATION UNIQUE PHONE
  private phoneUniqueValidator(): AsyncValidatorFn {
    return (control: AbstractControl): Observable<ValidationErrors | null> => {
      if (!control.value || control.pristine) {
        return of(null);
      }

      const currentPhone = this.profile()?.phoneNumber;
      if (control.value === currentPhone) {
        return of(null);
      }

      return timer(200).pipe(
        switchMap(() => this.profileService.checkPhoneTaken(control.value)),
        map(isTaken => {
          if (isTaken) {
            this.errorMessage.set('Ce numéro de téléphone est déjà associé à un autre compte.');
            this.autoClearMessage('error');
            return { phoneTaken: true };
          }
          return null;
        }),
        catchError(() => of(null))
      );
    };
  }

  // HELPERS (REQUIRED BY TEMPLATE)
  getInitials(): string {
    const p = this.profile();
    if (!p) return '?';
    return ((p.firstName?.[0] || '') + (p.lastName?.[0] || '')).toUpperCase() || '?';
  }

  getRoleLabel(roleName: string | undefined): string {
    switch (roleName) {
      case 'ADMIN': return 'Administrateur';
      case 'MANAGER': return 'Responsable du Risque';
      case 'ANALYST': return 'Analyste de Fraude';
      default: return 'Utilisateur';
    }
  }

  formatDate(date: string | null | undefined): string {
    if (!date) return '—';
    return new Date(date).toLocaleDateString('fr-FR', {
      day: '2-digit',
      month: 'long',
      year: 'numeric'
    });
  }

  formatDateTime(date: string | null | undefined): string {
    if (!date) return '—';
    return new Date(date).toLocaleString('fr-FR', {
      day: '2-digit',
      month: 'long',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  getPasswordStrength(): { label: string; class: string; width: string } {
    const password = this.passwordForm.get('newPassword')?.value || '';
    if (password.length === 0) return { label: '', class: '', width: '0%' };
    if (password.length < 8) return { label: 'Faible', class: 'strength-weak', width: '25%' };

    let score = 0;
    if (/[a-z]/.test(password)) score++;
    if (/[A-Z]/.test(password)) score++;
    if (/[0-9]/.test(password)) score++;
    if (/[^a-zA-Z0-9]/.test(password)) score++;

    if (score <= 1) return { label: 'Faible', class: 'strength-weak', width: '25%' };
    if (score === 2) return { label: 'Moyen', class: 'strength-medium', width: '50%' };
    if (score === 3) return { label: 'Fort', class: 'strength-strong', width: '75%' };
    return { label: 'Très fort', class: 'strength-very-strong', width: '100%' };
  }

  private clearMessages(): void {
    this.successMessage.set('');
    this.errorMessage.set('');
  }

  private clearPasswordMessages(): void {
    this.passwordSuccess.set('');
    this.passwordError.set('');
  }

  private autoClearMessage(type: string): void {
    setTimeout(() => {
      if (type === 'success') this.successMessage.set('');
      if (type === 'error') this.errorMessage.set('');
      if (type === 'passwordSuccess') this.passwordSuccess.set('');
      if (type === 'passwordError') this.passwordError.set('');
    }, 5000);
  }
}