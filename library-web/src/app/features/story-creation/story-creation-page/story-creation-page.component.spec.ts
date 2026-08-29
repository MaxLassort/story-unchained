import { describe, it, expect, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { StoryCreationPageComponent } from './story-creation-page.component';

describe('StoryCreationPageComponent', () => {
  it('renders the wizard with a three-step linear stepper', async () => {
    await TestBed.configureTestingModule({
      imports: [RouterTestingModule, StoryCreationPageComponent],
    }).compileComponents();

    const fixture = TestBed.createComponent(StoryCreationPageComponent);
    fixture.detectChanges();

    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('h1')?.textContent).toContain('Create a story');
    expect(root.querySelector('a')?.getAttribute('routerlink')).toBe('/packs');
    expect(root.querySelector('mat-stepper')).not.toBeNull();
    expect(root.querySelectorAll('mat-step-header').length).toBe(3);
  });

  it('disables the next action until the details step is complete', async () => {
    await TestBed.configureTestingModule({
      imports: [RouterTestingModule, StoryCreationPageComponent],
    }).compileComponents();

    const fixture = TestBed.createComponent(StoryCreationPageComponent);
    fixture.detectChanges();

    const root: HTMLElement = fixture.nativeElement;
    const nextButton: HTMLButtonElement | null = root.querySelector(
      'button[color="primary"]',
    );
    expect(nextButton?.disabled).toBe(true);

    const page = fixture.componentInstance;
    page.detailsStep()?.model.set({
      title: 'Mon histoire',
      description: '',
      titleAudio: null,
      menuAudio: null,
      thumbnail: new File(['x'], 'thumb.png', { type: 'image/png' }),
      cover: { mode: 'icon', iconId: 'star', file: null },
    });
    fixture.detectChanges();

    expect(nextButton?.disabled).toBe(false);
  });
});
