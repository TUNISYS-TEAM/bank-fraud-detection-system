import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DatasetViewModalComponent } from './dataset-view-modal.component';

describe('DatasetViewModalComponent', () => {
  let component: DatasetViewModalComponent;
  let fixture: ComponentFixture<DatasetViewModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [DatasetViewModalComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(DatasetViewModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
