import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ModelDetailsModalComponent } from './model-details-modal.component';

describe('ModelDetailsModalComponent', () => {
  let component: ModelDetailsModalComponent;
  let fixture: ComponentFixture<ModelDetailsModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ModelDetailsModalComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(ModelDetailsModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
