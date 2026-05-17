import { Component, inject, OnInit, OnDestroy, signal } from '@angular/core';
import { FormBuilder, FormGroup, FormControl, Validators } from '@angular/forms';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { AuthStore } from '../../../core/store/auth.store';
import { Subject, takeUntil } from 'rxjs';
import { environment } from '../../../../environments/environment';

@Component({
  selector: 'app-login',
  templateUrl: './login.component.html'
})
export class LoginComponent implements OnInit, OnDestroy {
  private fb = inject(FormBuilder);
  private http = inject(HttpClient);
  private router = inject(Router);
  private destroy$ = new Subject<void>();

  readonly authStore = inject(AuthStore);

  // Using signals for reactive state
  showPassword = signal<boolean>(false);
  showVerification = signal<boolean>(false);
  pendingEmail = signal<string>('');
  isVerifying = signal<boolean>(false);
  isResending = signal<boolean>(false);
  verificationMessage = signal<string>('');
  verificationIsError = signal<boolean>(false);
  otpRequested = signal<boolean>(false);
  
  // 6-digit OTP array as signal
  otpDigits = signal<string[]>(['', '', '', '', '', '']);

  // Nouveaux signaux pour le mot de passe oublié
  showForgotPassword = signal<boolean>(false);
  forgotPasswordMessage = signal<string>('');
  forgotPasswordError = signal<boolean>(false);
  isSendingForgotPassword = signal<boolean>(false);

  loginForm: FormGroup = this.fb.group({
    username: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(6)]]
  });

  verificationForm: FormGroup = this.fb.group({
    code: ['', [Validators.required, Validators.pattern('^[0-9]{6}$')]]
  });

  // Nouveau contrôle pour l'email de réinitialisation
  forgotEmailControl = new FormControl('', [Validators.required, Validators.email]);

  ngOnInit(): void {
    if (this.isElectron()) {
      const { ipcRenderer } = (window as any).require('electron');
      ipcRenderer.send('unmaximize-window');
    }
    this.authStore.clearError();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  /**
   * TrackBy function for OTP inputs
   */
  trackByIndex(index: number, item: string): number {
    return index;
  }

  togglePassword(): void {
    this.showPassword.update(value => !value);
  }

  // ==================== ENVOI MOT DE PASSE OUBLIÉ ====================
  sendForgotPassword(): void {
    if (this.forgotEmailControl.invalid || this.isSendingForgotPassword()) return;

    this.isSendingForgotPassword.set(true);
    this.forgotPasswordMessage.set('');
    this.forgotPasswordError.set(false);

    const email = this.forgotEmailControl.value?.trim();
    if (!email) {
      this.forgotPasswordMessage.set('Veuillez saisir un email.');
      this.forgotPasswordError.set(true);
      this.isSendingForgotPassword.set(false);
      return;
    }

    this.http.post(`${environment.apiUrl}/auth/forgot-password`, { email })
      .subscribe({
        next: (response: any) => {
          this.forgotPasswordMessage.set(response.message || 'Un email vous a été envoyé.');
          this.forgotPasswordError.set(false);
          this.isSendingForgotPassword.set(false);
          this.forgotEmailControl.reset();
        },
        error: (error: HttpErrorResponse) => {
          this.forgotPasswordMessage.set(error.error?.error || 'Une erreur est survenue.');
          this.forgotPasswordError.set(true);
          this.isSendingForgotPassword.set(false);
        }
      });
  }

  // ==================== LOGIN ====================
  onSubmit(): void {
    if (this.loginForm.valid) {
      const credentials = {
        email: this.loginForm.value.username.trim(),
        password: this.loginForm.value.password
      };

      this.authStore.login(credentials).subscribe({
        next: (result) => {
          if (result.user) {
            this.router.navigate(['/dashboard/overview']);
          }
        },
        error: (error: HttpErrorResponse) => {
          // Handle email not verified case gracefully
          if (error.status === 403 && error.error?.error === 'EMAIL_NOT_VERIFIED') {
            this.pendingEmail.set(credentials.email);
            this.showVerification.set(true);
            this.verificationMessage.set(error.error?.message || 'Veuillez vérifier votre email.');
            this.verificationIsError.set(false);
            this.otpRequested.set(false);
            // Reset OTP digits
            this.otpDigits.set(['', '', '', '', '', '']);

            // Auto-request OTP when verification screen appears
            setTimeout(() => {
              if (this.showVerification() && !this.otpRequested()) {
                this.requestOTP();
              }
            }, 500);
            return;
          }
          
          // Handle other errors normally
          console.error('Login error:', error);
          this.verificationMessage.set(error.error?.message || 'Email ou mot de passe incorrect');
          this.verificationIsError.set(true);
          this.showVerification.set(false);
        }
      });
    } else {
      Object.keys(this.loginForm.controls).forEach(key => {
        this.loginForm.get(key)?.markAsTouched();
      });
    }
  }

  // ==================== OTP ====================
  isOtpValid(): boolean {
    const code = this.otpDigits().join('');
    return code.length === 6 && /^\d{6}$/.test(code);
  }

  getOtpCode(): string {
    return this.otpDigits().join('');
  }

  onOtpInput(index: number, event: Event): void {
    const input = event.target as HTMLInputElement;
    const value = input.value;
    const currentDigits = [...this.otpDigits()];
    
    // Only allow digits
    if (value && !/^\d$/.test(value)) {
      currentDigits[index] = '';
      this.otpDigits.set(currentDigits);
      input.value = '';
      return;
    }
    
    currentDigits[index] = value;
    this.otpDigits.set(currentDigits);
    input.value = value;
    
    // Auto-focus next input if current is filled
    if (value && index < 5) {
      const inputs = document.querySelectorAll('.otp-input');
      const nextInput = inputs[index + 1] as HTMLInputElement;
      if (nextInput) {
        nextInput.focus();
      }
    }
    
    // Auto-verify when all 6 digits are filled
    if (this.isOtpValid()) {
      this.onVerifyCode();
    }
  }

  onOtpKeydown(index: number, event: KeyboardEvent): void {
    if (event.key === 'Backspace' && !this.otpDigits()[index] && index > 0) {
      const inputs = document.querySelectorAll('.otp-input');
      const prevInput = inputs[index - 1] as HTMLInputElement;
      if (prevInput) {
        prevInput.focus();
      }
    }
  }

  onOtpPaste(event: ClipboardEvent): void {
    event.preventDefault();
    const pastedData = event.clipboardData?.getData('text/plain').trim() || '';
    
    if (/^\d{6}$/.test(pastedData)) {
      const digits = pastedData.split('');
      this.otpDigits.set(digits);
      
      const inputs = document.querySelectorAll('.otp-input');
      digits.forEach((digit: string, idx: number) => {
        if (inputs[idx]) {
          (inputs[idx] as HTMLInputElement).value = digit;
        }
      });
      
      this.onVerifyCode();
    }
  }

  requestOTP(): void {
    if (this.isResending()) return;

    this.isResending.set(true);
    this.verificationMessage.set('');
    this.otpRequested.set(true);

    this.http.post(`${environment.apiUrl}/auth/request-otp`, { email: this.pendingEmail() })
      .subscribe({
        next: (response: any) => {
          this.verificationMessage.set(response.message || 'Un code de vérification a été envoyé à votre email.');
          this.verificationIsError.set(false);
          this.isResending.set(false);

          setTimeout(() => {
            if (this.verificationMessage() && !this.verificationIsError() && this.showVerification()) {
              this.verificationMessage.set('');
            }
          }, 5000);
        },
        error: (error: HttpErrorResponse) => {
          this.verificationMessage.set(error.error?.message || 'Erreur lors de l\'envoi du code. Veuillez réessayer.');
          this.verificationIsError.set(true);
          this.isResending.set(false);
          this.otpRequested.set(false);
        }
      });
  }

  onVerifyCode(): void {
    const code = this.getOtpCode();
    
    if (!this.isOtpValid() || this.isVerifying()) {
      return;
    }

    this.isVerifying.set(true);
    this.verificationMessage.set('');

    this.http.post(`${environment.apiUrl}/auth/verify-otp`, {
      email: this.pendingEmail(),
      code: code
    }).subscribe({
      next: (response: any) => {
        this.verificationMessage.set(response.message || 'Email vérifié avec succès !');
        this.verificationIsError.set(false);
        this.isVerifying.set(false);

        setTimeout(() => {
          this.showVerification.set(false);
          this.otpDigits.set(['', '', '', '', '', '']);
          this.otpRequested.set(false);

          // Auto-login after successful verification
          const credentials = {
            email: this.pendingEmail(),
            password: this.loginForm.value.password
          };

          this.authStore.login(credentials).subscribe({
            next: (result) => {
              if (result.user) {
                this.router.navigate(['/dashboard/overview']);
              }
            },
            error: () => {
              this.loginForm.reset();
            }
          });
        }, 2000);
      },
      error: (error: HttpErrorResponse) => {
        this.verificationMessage.set(error.error?.message || 'Code invalide. Veuillez réessayer.');
        this.verificationIsError.set(true);
        this.isVerifying.set(false);
        // Clear the digits on error to allow retry
        this.otpDigits.set(['', '', '', '', '', '']);
        // Clear input fields
        const inputs = document.querySelectorAll('.otp-input');
        inputs.forEach(input => {
          (input as HTMLInputElement).value = '';
        });
        // Focus first input
        setTimeout(() => {
          const firstInput = document.querySelector('.otp-input') as HTMLInputElement;
          if (firstInput) {
            firstInput.focus();
          }
        }, 100);
      }
    });
  }

  resendVerification(): void {
    this.requestOTP();
  }

  backToLogin(): void {
    this.showVerification.set(false);
    this.otpDigits.set(['', '', '', '', '', '']);
    this.verificationMessage.set('');
    this.otpRequested.set(false);
  }

  closeWindow(): void {
    if (this.isElectron()) {
      const { ipcRenderer } = (window as any).require('electron');
      ipcRenderer.send('close-window');
    } else {
      window.close();
    }
  }

  private isElectron(): boolean {
    return !!(window as any).require;
  }

  // ==================== ERROR GETTERS ====================
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

  get codeError(): string {
    const code = this.otpDigits().join('');
    if (code.length > 0 && code.length < 6) {
      return 'Code incomplet (6 chiffres requis)';
    }
    if (code.length === 6 && !/^\d{6}$/.test(code)) {
      return 'Le code doit contenir uniquement des chiffres';
    }
    return '';
  }
}