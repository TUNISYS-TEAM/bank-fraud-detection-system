import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ClientsViewModalComponent } from './clients-view-modal.component';

describe('ClientsViewModalComponent', () => {
  let component: ClientsViewModalComponent;
  let fixture: ComponentFixture<ClientsViewModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ClientsViewModalComponent]
    })
    .compileComponents();
    
    fixture = TestBed.createComponent(ClientsViewModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
