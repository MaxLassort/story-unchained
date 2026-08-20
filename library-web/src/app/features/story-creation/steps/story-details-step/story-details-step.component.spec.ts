import { describe, it, expect } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { StoryDetailsStepComponent } from './story-details-step.component';

describe('StoryDetailsStepComponent', () => {
  async function createComponent() {
    await TestBed.configureTestingModule({
      imports: [StoryDetailsStepComponent],
    }).compileComponents();
    const fixture = TestBed.createComponent(StoryDetailsStepComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('renders the title, description and title audio sections', async () => {
    const fixture = await createComponent();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('h2')?.textContent).toContain('Story Details');
    expect(root.querySelectorAll('mat-form-field').length).toBe(3);
    expect(root.querySelector('app-title-audio-input')).not.toBeNull();
  });

  it('requires a non-blank title for the step to be complete', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    expect(component.detailsForm().valid()).toBe(false);
    expect(component.complete()).toBe(false);

    component.model.set({ title: '', description: 'x', titleAudio: null });
    fixture.detectChanges();
    expect(component.complete()).toBe(false);

    component.model.set({ title: 'Ma petite histoire', description: 'x', titleAudio: null });
    fixture.detectChanges();
    expect(component.complete()).toBe(true);
    expect(component.detailsForm().errors().length).toBe(0);
  });
});