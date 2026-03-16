import { ComponentFixture, TestBed } from '@angular/core/testing';

import { HistoryUpdateModalComponent } from './history-update-modal.component';

describe('HistoryUpdateModalComponent', () => {
  let component: HistoryUpdateModalComponent;
  let fixture: ComponentFixture<HistoryUpdateModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [HistoryUpdateModalComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(HistoryUpdateModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
