import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DatasetUpdateModalComponent } from './dataset-update-modal.component';

describe('DatasetUpdateModalComponent', () => {
  let component: DatasetUpdateModalComponent;
  let fixture: ComponentFixture<DatasetUpdateModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [DatasetUpdateModalComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(DatasetUpdateModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
