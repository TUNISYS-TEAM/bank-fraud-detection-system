import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SettingsDeleteModalComponent } from './settings-delete-modal.component';

describe('SettingsDeleteModalComponent', () => {
  let component: SettingsDeleteModalComponent;
  let fixture: ComponentFixture<SettingsDeleteModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [SettingsDeleteModalComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(SettingsDeleteModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
