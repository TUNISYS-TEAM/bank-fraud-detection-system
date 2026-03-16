import { ComponentFixture, TestBed } from '@angular/core/testing';

import { HistoryDeleteModalComponent } from './history-delete-modal.component';

describe('HistoryDeleteModalComponent', () => {
  let component: HistoryDeleteModalComponent;
  let fixture: ComponentFixture<HistoryDeleteModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [HistoryDeleteModalComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(HistoryDeleteModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
