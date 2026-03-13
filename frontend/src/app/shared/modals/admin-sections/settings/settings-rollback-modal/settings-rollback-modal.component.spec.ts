import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SettingsRollbackModalComponent } from './settings-rollback-modal.component';

describe('SettingsRollbackModalComponent', () => {
  let component: SettingsRollbackModalComponent;
  let fixture: ComponentFixture<SettingsRollbackModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [SettingsRollbackModalComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(SettingsRollbackModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
