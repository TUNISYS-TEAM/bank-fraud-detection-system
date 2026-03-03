import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DatasetCreateModalComponent } from './dataset-create-modal.component';

describe('DatasetCreateModalComponent', () => {
  let component: DatasetCreateModalComponent;
  let fixture: ComponentFixture<DatasetCreateModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [DatasetCreateModalComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(DatasetCreateModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
