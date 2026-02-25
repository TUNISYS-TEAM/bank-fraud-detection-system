import { Component, inject } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { AuthStore } from '../../../core/store/auth.store';

@Component({
  selector: 'app-login',
  templateUrl: './login.component.html'
})
export class LoginComponent {
  private fb = inject(FormBuilder);
  readonly authStore = inject(AuthStore);

  showPassword = false;
  
  loginForm: FormGroup = this.fb.group({
    username: ['', [Validators.required]], 
    password: ['', [Validators.required, Validators.minLength(6)]]
  });

  togglePassword(): void {
    this.showPassword = !this.showPassword;
  }

  onSubmit(): void {
    if (this.loginForm.valid) {
      const credentials = {
        email: this.loginForm.value.username,  
        password: this.loginForm.value.password
      };
      this.authStore.login(credentials);
    }
  }

  closeWindow(): void {
    window.close();
  }

  get usernameErrors(): string {
    const control = this.loginForm.get('username');
    if (control?.touched && control?.errors) {
      if (control.errors['required']) return "Nom d'utilisateur requis";
    }
    return '';
  }

  get passwordErrors(): string {
    const control = this.loginForm.get('password');
    if (control?.touched && control?.errors) {
      if (control.errors['required']) return 'Mot de passe requis';
      if (control.errors['minlength']) return 'Minimum 6 caractères';
    }
    return '';
  }
}