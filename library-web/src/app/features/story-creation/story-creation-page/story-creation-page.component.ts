import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatStepperModule, MatStepper } from '@angular/material/stepper';
import { StoryDraftService } from '../../../core/services/story-draft.service';
import { StoryDetailsStepComponent } from '../steps/story-details-step/story-details-step.component';
import { BulkAudioStepComponent } from '../steps/bulk-audio-step/bulk-audio-step.component';
import { ChaptersStepComponent } from '../steps/chapters-step/chapters-step.component';
import { ChaptersEditorState } from '../chapters-editor-state.service';

@Component({
  selector: 'app-story-creation-page',
  imports: [
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatStepperModule,
    StoryDetailsStepComponent,
    BulkAudioStepComponent,
    ChaptersStepComponent,
  ],
  providers: [ChaptersEditorState],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './story-creation-page.component.html',
  styleUrl: './story-creation-page.component.scss',
})
export class StoryCreationPageComponent {
  private readonly router = inject(Router);
  private readonly drafts = inject(StoryDraftService);
  private readonly snackBar = inject(MatSnackBar);

  readonly detailsStep = viewChild(StoryDetailsStepComponent);
  readonly bulkStep = viewChild(BulkAudioStepComponent);
  readonly chaptersStep = viewChild(ChaptersStepComponent);
  readonly stepper = viewChild(MatStepper);

  readonly finalizing = signal(false);
  readonly finalizeError = signal<string | null>(null);

  protected readonly canFinalize = computed(
    () => !!this.drafts.draftId() && !this.finalizing(),
  );

  protected cancel(): void {
    void this.router.navigate(['/packs']);
  }

  async saveDetailsAndNext(): Promise<void> {
    const step = this.detailsStep();
    if (!step) return;
    const ok = await step.save();
    if (ok) {
      this.stepper()?.next();
    }
  }

  async saveChaptersAndNext(): Promise<void> {
    const step = this.chaptersStep();
    if (!step) return;
    const ok = await step.save();
    if (ok) {
      this.stepper()?.next();
    }
  }

  /** Bulk step pre-fills the shared chapters state; confirming saves staged chapters and moves to Chapters step. */
  async confirmBulkAndNext(): Promise<void> {
    const step = this.bulkStep();
    if (!step) return;
    const ok = await step.save();
    if (ok) {
      this.stepper()?.next();
    }
  }

  async createPack(): Promise<void> {
    const draftId = this.drafts.draftId();
    if (!draftId || this.finalizing()) return;
    this.finalizing.set(true);
    this.finalizeError.set(null);
    try {
      const { packId } = await this.drafts.finalizeDraft(draftId);
      this.drafts.draftId.set(null);
      this.snackBar.open('Story created successfully!', 'Close', {
        duration: 4000,
        panelClass: 'snackbar-success',
      });
      void this.router.navigate(['/packs', packId]);
    } catch (err) {
      const msg =
        err instanceof HttpErrorResponse
          ? (err.error?.error ?? err.error?.message ?? err.message)
          : null;
      this.finalizeError.set(
        msg ?? 'Could not create the pack. Make sure every step is complete, then try again.',
      );
    } finally {
      this.finalizing.set(false);
    }
  }
}
