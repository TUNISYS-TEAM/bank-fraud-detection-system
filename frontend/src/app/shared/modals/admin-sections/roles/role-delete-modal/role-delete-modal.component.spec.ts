import { ComponentFixture, TestBed } from '@angular/core/testing';

import { RoleDeleteModalComponent } from './role-delete-modal.component';

describe('RoleDeleteModalComponent', () => {
  let component: RoleDeleteModalComponent;
  let fixture: ComponentFixture<RoleDeleteModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [RoleDeleteModalComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(RoleDeleteModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
