import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AnalystAlertDetailsModalComponent } from './analyst-alert-details-modal.component';

describe('AnalystAlertDetailsModalComponent', () => {
  let component: AnalystAlertDetailsModalComponent;
  let fixture: ComponentFixture<AnalystAlertDetailsModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [AnalystAlertDetailsModalComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(AnalystAlertDetailsModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
