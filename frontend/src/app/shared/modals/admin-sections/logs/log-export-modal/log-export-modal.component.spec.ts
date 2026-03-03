import { ComponentFixture, TestBed } from '@angular/core/testing';

import { LogExportModalComponent } from './log-export-modal.component';

describe('LogExportModalComponent', () => {
  let component: LogExportModalComponent;
  let fixture: ComponentFixture<LogExportModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [LogExportModalComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(LogExportModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
