import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AnalyzedTransactionsComponent } from './analyzed-transactions.component';

describe('AnalyzedTransactionsComponent', () => {
  let component: AnalyzedTransactionsComponent;
  let fixture: ComponentFixture<AnalyzedTransactionsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [AnalyzedTransactionsComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(AnalyzedTransactionsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
