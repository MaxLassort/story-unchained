import { Component, computed, signal } from '@angular/core';
import { form, FormField, required } from '@angular/forms/signals';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { TitleAudioInputComponent, TitleAudioSelection } from '../../components/title-audio-input/title-audio-input.component';

export interface StoryDetailsModel {
  title: string;
  description: string;
  titleAudio: TitleAudioSelection | null;
}

@Component({
  selector: 'app-story-details-step',
  imports: [FormField, MatFormFieldModule, MatInputModule, TitleAudioInputComponent],
  templateUrl: './story-details-step.component.html',
  styleUrl: './story-details-step.component.scss',
})
export class StoryDetailsStepComponent {
  readonly model = signal<StoryDetailsModel>({
    title: '',
    description: '',
    titleAudio: null,
  });

  readonly detailsForm = form(this.model, (schemaPath) => {
    required(schemaPath.title, { message: 'Title is required' });
    required(schemaPath.description, { message: 'description is required' });
    required(schemaPath.titleAudio, { message: 'Audio title is required' });
  });

  /** Whether the step is complete (drives the linear stepper). */
  readonly complete = computed(() => this.detailsForm().valid());
}
