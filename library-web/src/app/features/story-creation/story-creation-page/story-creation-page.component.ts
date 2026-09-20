import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal, viewChild } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatStepperModule, MatStepper } from '@angular/material/stepper';
import { StoryDraftService } from '../../../core/services/story-draft.service';
import { StoryDetailsStepComponent } from '../steps/story-details-step/story-details-step.component';
import { AudioUploadStepComponent } from '../steps/audio-upload-step/audio-upload-step.component';
import { ChaptersStepComponent } from '../steps/chapters-step/chapters-step.component';
import { ChaptersEditorState } from '../chapters-editor-state.service';
import { TranslatePipe } from '../../../core/pipes/translate.pipe';

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
    AudioUploadStepComponent,
    ChaptersStepComponent,
    TranslatePipe,
  ],
  providers: [ChaptersEditorState],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './story-creation-page.component.html',
  styleUrl: './story-creation-page.component.scss',
})
export class StoryCreationPageComponent implements OnInit {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly drafts = inject(StoryDraftService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly chaptersState = inject(ChaptersEditorState);

  readonly detailsStep = viewChild(StoryDetailsStepComponent);
  readonly uploadStep = viewChild(AudioUploadStepComponent);
  readonly chaptersStep = viewChild(ChaptersStepComponent);
  readonly stepper = viewChild(MatStepper);

  /** When set, finalize rewrites this Unchained pack instead of creating a new one. */
  readonly editPackId = signal<string | null>(null);
  readonly booting = signal(true);
  readonly bootError = signal<string | null>(null);
  readonly finalizing = signal(false);
  readonly finalizeError = signal<string | null>(null);

  readonly isEditMode = computed(() => this.editPackId() !== null);

  protected readonly canFinalize = computed(
    () => !!this.drafts.draftId() && !this.finalizing() && !this.booting(),
  );

  async ngOnInit(): Promise<void> {
    this.booting.set(true);
    this.bootError.set(null);
    try {
      const resumeDraftId = this.route.snapshot.paramMap.get('draftId');
      const packId = this.route.snapshot.paramMap.get('packId');

      if (resumeDraftId) {
        const draft = await this.drafts.getDraft(resumeDraftId);
        this.drafts.selectDraft(resumeDraftId);
        if (draft.sourcePackId) this.editPackId.set(draft.sourcePackId);
      } else if (packId) {
        this.editPackId.set(packId);
        this.drafts.draftId.set(null);
        await this.drafts.createDraftFromPack(packId);
      } else {
        // Create Story: always a fresh empty draft (never reopen another draft).
        this.editPackId.set(null);
        this.drafts.draftId.set(null);
        await this.drafts.ensureDraft();
      }

      await this.chaptersState.loadExistingDraft();
    } catch (err) {
      const msg =
        err instanceof HttpErrorResponse
          ? (err.error?.error ?? err.error?.message ?? err.message)
          : null;
      this.bootError.set(msg ?? 'Could not open this story for editing.');
    } finally {
      this.booting.set(false);
    }
  }

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

  /** Upload step pre-fills the shared chapters state; confirming saves staged chapters and moves to Chapters step. */
  async confirmUploadAndNext(): Promise<void> {
    const step = this.uploadStep();
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
      const replacePackId = this.editPackId() ?? undefined;
      const { packId } = await this.drafts.finalizeDraft(draftId, replacePackId);
      this.drafts.draftId.set(null);
      this.snackBar.open(
        replacePackId ? 'Story updated successfully!' : 'Story created successfully!',
        'Close',
        {
          duration: 4000,
          panelClass: 'snackbar-success',
        },
      );
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
