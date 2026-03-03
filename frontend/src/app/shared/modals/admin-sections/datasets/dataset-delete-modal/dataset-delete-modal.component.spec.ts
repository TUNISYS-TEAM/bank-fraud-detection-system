import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DatasetDeleteModalComponent } from './dataset-delete-modal.component';

describe('DatasetDeleteModalComponent', () => {
  let component: DatasetDeleteModalComponent;
  let fixture: ComponentFixture<DatasetDeleteModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [DatasetDeleteModalComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(DatasetDeleteModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
