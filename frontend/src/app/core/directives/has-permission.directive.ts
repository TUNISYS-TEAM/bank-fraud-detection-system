// shared/directives/has-permission.directive.ts
import { Directive, Input, TemplateRef, ViewContainerRef, OnInit, inject } from '@angular/core';
import { AuthStore } from '../../core/store/auth.store';

@Directive({
  selector: '[appHasPermission]',
  standalone: true
})
export class HasPermissionDirective implements OnInit {
  private authStore = inject(AuthStore);
  private templateRef = inject(TemplateRef<any>);
  private viewContainer = inject(ViewContainerRef);

  @Input() appHasPermission: string | string[] = [];
  @Input() appHasPermissionMode: 'any' | 'all' = 'any';

  ngOnInit(): void {
    this.updateView();
  }

  private updateView(): void {
    const hasPermission = this.checkPermission();

    if (hasPermission) {
      this.viewContainer.createEmbeddedView(this.templateRef);
    } else {
      this.viewContainer.clear();
    }
  }

  private checkPermission(): boolean {
    if (!this.appHasPermission) {
      return true;
    }

    const permissions = Array.isArray(this.appHasPermission) 
      ? this.appHasPermission 
      : [this.appHasPermission];

    if (this.appHasPermissionMode === 'all') {
      return this.authStore.hasAllPermissions(permissions);
    } else {
      return this.authStore.hasAnyPermission(permissions);
    }
  }
}