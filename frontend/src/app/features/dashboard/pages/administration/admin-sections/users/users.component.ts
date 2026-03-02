import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../../environments/environment';
import { AuthStore } from '../../../../../../core/store/auth.store';
import { CreateUserData } from '../../../../../../shared/modals/admin-sections/users/user-create-modal/user-create-modal.component';
import { DeleteUserData } from '../../../../../../shared/modals/admin-sections/users/user-delete-modal/user-delete-modal.component';

interface User {
  id: number;
  uuid: string;
  email: string;
  firstName: string;
  lastName: string;
  roleId: number;
  isActive: boolean;
  lastLogin: string | null;
}

interface ApiResponse {
  content: User[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

interface Role {
  id: number;
  name: string;
  description: string;
}

@Component({
  selector: 'app-users',
  templateUrl: './users.component.html',
  styleUrl: './users.component.css'
})
export class UsersComponent implements OnInit {
  private http = inject(HttpClient);
  authStore = inject(AuthStore);

  // Signaux pour l'état local
  users = signal<User[]>([]);
  loading = signal(false);
  currentPage = signal(0);
  pageSize = 20;
  totalElements = signal(0);
  totalPages = signal(0);

  // Cache des rôles
  private rolesCache = signal<Map<number, Role>>(new Map());
  rolesLoaded = signal(false);

  // Modals state
  showCreateModal = signal(false);
  showDeleteModal = signal(false);
  showViewModal = signal(false); // AJOUTÉ
  userToDelete = signal<DeleteUserData | null>(null);
  selectedUserId = signal<number | null>(null); // AJOUTÉ

  // Computed values
  displayRange = computed(() => {
    const start = this.currentPage() * this.pageSize + 1;
    const end = Math.min((this.currentPage() + 1) * this.pageSize, this.totalElements());
    return { start, end };
  });

  hasUsers = computed(() => this.users().length > 0);
  isLoading = computed(() => this.loading());

  // Fonction pour obtenir le nom du rôle depuis le cache
  getRoleName = (roleId: number): string => {
    const role = this.rolesCache().get(roleId);
    return role?.name || 'Chargement...';
  };

  // Fonction pour obtenir la classe CSS du rôle
  getRoleClass = (roleId: number): string => {
    const role = this.rolesCache().get(roleId);
    switch (role?.name) {
      case 'ADMIN': return 'bg-purple-50 text-purple-700 border-purple-200';
      case 'MANAGER': return 'bg-blue-50 text-blue-700 border-blue-200';
      case 'ANALYST': return 'bg-amber-50 text-amber-700 border-amber-200';
      default: return 'bg-gray-50 text-gray-700 border-gray-200';
    }
  };

  ngOnInit(): void {
    this.loadRoles();
  }

  // Charger les rôles une seule fois
  loadRoles(): void {
    if (this.rolesLoaded()) return;

    this.http.get<Role[]>(`${environment.apiUrl}/roles`).subscribe({
      next: (roles) => {
        const roleMap = new Map(roles.map(role => [role.id, role]));
        this.rolesCache.set(roleMap);
        this.rolesLoaded.set(true);
        this.loadUsers();
      },
      error: (error) => {
        console.error('Error loading roles:', error);
        this.loadUsers();
      }
    });
  }

  // Charger les utilisateurs
  loadUsers(): void {
    this.loading.set(true);
    const url = `${environment.apiUrl}/users?page=${this.currentPage()}&size=${this.pageSize}`;

    this.http.get<ApiResponse>(url).subscribe({
      next: (response) => {
        this.users.set(response.content);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
        this.loading.set(false);
      },
      error: (error) => {
        console.error('Error loading users:', error);
        this.loading.set(false);
      }
    });
  }

  onPageChange(page: number): void {
    this.currentPage.set(page);
    this.loadUsers();
  }

  formatDate(date: string | null): string {
    if (!date) return 'Jamais';
    return new Date(date).toLocaleString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  // Create Modal methods
  openCreateModal(): void {
    this.showCreateModal.set(true);
  }

  closeCreateModal(): void {
    this.showCreateModal.set(false);
  }

  onUserCreated(userData: CreateUserData): void {
    this.loadUsers();
    this.closeCreateModal();
  }

  // Delete Modal methods
  openDeleteModal(user: User): void {
    this.userToDelete.set({
      id: user.id,
      firstName: user.firstName,
      lastName: user.lastName,
      email: user.email
    });
    this.showDeleteModal.set(true);
  }

  closeDeleteModal(): void {
    this.showDeleteModal.set(false);
    setTimeout(() => this.userToDelete.set(null), 300);
  }

  onUserDeleted(userId: number): void {
    this.loadUsers();
    this.closeDeleteModal();
  }

  // View Modal methods - AJOUTÉS
  openViewModal(user: User): void {
    this.selectedUserId.set(user.id);
    this.showViewModal.set(true);
  }

  closeViewModal(): void {
    this.showViewModal.set(false);
    setTimeout(() => this.selectedUserId.set(null), 300);
  }

  editUser(user: User): void {
    console.log('Edit user:', user);
    // TODO: Implémenter le modal d'édition
  }

  toggleUserStatus(user: User): void {
    const action = user.isActive ? 'désactiver' : 'activer';
    if (confirm(`Êtes-vous sûr de vouloir ${action} l'utilisateur ${user.firstName} ${user.lastName} ?`)) {
      console.log('Toggle status for user:', user);
      // TODO: Implémenter l'appel API
    }
  }

  getPageNumbers(): number[] {
    const total = this.totalPages();
    const current = this.currentPage() + 1;
    const delta = 2;
    const range: number[] = [];
    const rangeWithDots: number[] = [];
    let l: number;

    for (let i = 1; i <= total; i++) {
      if (i === 1 || i === total || (i >= current - delta && i <= current + delta)) {
        range.push(i);
      }
    }

    range.forEach((i) => {
      if (l) {
        if (i - l === 2) {
          rangeWithDots.push(l + 1);
        } else if (i - l !== 1) {
          rangeWithDots.push(-1);
        }
      }
      rangeWithDots.push(i);
      l = i;
    });

    return rangeWithDots;
  }

  getDisplayId(user: User): string {
    return `USR-${user.id.toString().padStart(3, '0')}`;
  }
}