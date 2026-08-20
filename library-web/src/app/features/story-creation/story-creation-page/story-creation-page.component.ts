import { Component, inject, viewChild } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatStepperModule } from '@angular/material/stepper';
import { StoryDetailsStepComponent } from '../steps/story-details-step/story-details-step.component';

@Component({
  selector: 'app-story-creation-page',
  imports: [RouterLink, MatButtonModule, MatCardModule, MatIconModule, MatStepperModule, StoryDetailsStepComponent],
  templateUrl: './story-creation-page.component.html',
  styleUrl: './story-creation-page.component.scss',
})
export class StoryCreationPageComponent {
  private readonly router = inject(Router);
  readonly detailsStep = viewChild(StoryDetailsStepComponent);

  protected cancel(): void {
    void this.router.navigate(['/packs']);
  }
}
