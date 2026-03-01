import { Component, inject, OnInit, OnDestroy } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { AuthStore } from '../../../core/store/auth.store';
import { Subject, takeUntil } from 'rxjs';

@Component({
  selector: 'app-login',
  templateUrl: './login.component.html'
})
export class LoginComponent implements OnInit, OnDestroy {
  private fb = inject(FormBuilder);
  private destroy$ = new Subject<void>();
  
  readonly authStore = inject(AuthStore);

  showPassword = false;

  loginForm: FormGroup = this.fb.group({
    username: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(6)]]
  });

  ngOnInit(): void {
    // Unmaximize window for Electron
    if (this.isElectron()) {
      const { ipcRenderer } = (window as any).require('electron');
      ipcRenderer.send('unmaximize-window');
    }

    // Clear any previous errors
    this.authStore.clearError();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  /**
   * Toggle password visibility
   */
  togglePassword(): void {
    this.showPassword = !this.showPassword;
  }

  /**
   * Handle form submission
   */
  onSubmit(): void {
    if (this.loginForm.valid) {
      const credentials = {
        email: this.loginForm.value.username.trim(),
        password: this.loginForm.value.password
      };
      
      this.authStore.login(credentials);
    } else {
      // Mark all fields as touched to show validation errors
      Object.keys(this.loginForm.controls).forEach(key => {
        this.loginForm.get(key)?.markAsTouched();
      });
    }
  }

  /**
   * Close window (for Electron app)
   */
  closeWindow(): void {
    if (this.isElectron()) {
      const { ipcRenderer } = (window as any).require('electron');
      ipcRenderer.send('close-window');
    } else {
      window.close();
    }
  }

  /**
   * Check if running in Electron
   */
  private isElectron(): boolean {
    return !!(window as any).require;
  }

  /**
   * Get username field errors
   */
  get usernameErrors(): string {
    const control = this.loginForm.get('username');
    if (control?.touched && control?.errors) {
      if (control.errors['required']) {
        return "Email requis";
      }
      if (control.errors['email']) {
        return "Email invalide";
      }
    }
    return '';
  }

  /**
   * Get password field errors
   */
  get passwordErrors(): string {
    const control = this.loginForm.get('password');
    if (control?.touched && control?.errors) {
      if (control.errors['required']) {
        return 'Mot de passe requis';
      }
      if (control.errors['minlength']) {
        return 'Minimum 6 caractères';
      }
    }
    return '';
  }
}