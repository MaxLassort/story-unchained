import { Component, computed, DestroyRef, effect, inject, input, model, signal, untracked } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { FormValueControl } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { environment } from '../../../../../environments/environment';
import { PacksService } from '../../../../core/services/packs.service';
import type { ChapterIconsResponse, NodeImageMode, NodeImageSelection } from '../../../../core/models';

/**
 * Reusable Lunii node image picker (Signal Forms `FormValueControl`): the user
 * either selects a Lucide icon from the catalog (searchable) or uploads a custom
 * PNG/JPEG image. The uploaded image is validated against the expected Lunii
 * node dimensions (320×240 by default). The value holds both fields so switching
 * modes does not lose the previous selection.
 */
@Component({
  selector: 'app-node-image-input',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './node-image-input.component.html',
  styleUrl: './node-image-input.component.scss',
})
export class NodeImageInputComponent implements FormValueControl<NodeImageSelection | null> {
  private readonly destroyRef = inject(DestroyRef);
  private readonly packs = inject(PacksService);

  readonly label = input('Node image');
  readonly expectedWidth = input(320);
  readonly expectedHeight = input(240);

  /**
   * When set (e.g. from the chapters step), adds a "Chapter number" mode option
   * that renders the given number as a white-on-black image.
   */
  readonly chapterNumber = input<number | null>(null);


  readonly imageSpecTooltip = computed(() =>
    `Custom images must be PNG or JPEG (or an SVG that will be converted to PNG), ` +
    `exactly ${this.expectedWidth()}×${this.expectedHeight()} px. ` +
    'This is the native Lunii display resolution. Images are converted to 4-bpp RLE on the device.',
  );

  readonly chapterNumberTooltip =
    'Renders the chapter number as a white-on-black PNG at Lunii node resolution. ' +
    'This option is only available when configuring a chapter.';

  readonly value = model.required<NodeImageSelection | null>();

  readonly mode = computed<NodeImageMode>(() => this.value()?.mode ?? 'icon');
  protected readonly iconId = computed(() => this.value()?.iconId ?? null);
  protected readonly file = computed(() => this.value()?.file ?? null);
  protected readonly selectedChapterNumber = computed(() => this.value()?.chapterNumber ?? null);

  readonly searchQuery = signal('');
  readonly dragging = signal(false);
  readonly typeError = signal<string | null>(null);
  readonly dimensionError = signal<string | null>(null);
  readonly validating = signal(false);
  readonly converting = signal(false);
  readonly convertError = signal<string | null>(null);
  readonly previewUrl = signal<string | null>(null);

  private previewedFile: File | null = null;
  private readonly imagesUrl = `${environment.apiUrl}/stories/images`;

  private readonly iconsResource = httpResource<ChapterIconsResponse>(() => {
    const q = this.searchQuery().trim();
    if (q.length >= 2) {
      return { url: `${this.imagesUrl}/icons/search`, params: { q } };
    }
    return { url: `${this.imagesUrl}/icons` };
  });
  readonly icons = computed(() =>
    this.iconsResource.hasValue() ? (this.iconsResource.value()?.icons ?? []) : [],
  );
  readonly iconsLoading = this.iconsResource.isLoading;

  protected readonly selectedIconName = computed(() => {
    const id = this.iconId();
    if (!id) return null;
    return this.icons().find((i) => i.id === id)?.name ?? id;
  });

  constructor() {
    effect(() => {
      const f = this.file();
      untracked(() => this.setPreview(f));
    });
    // Keep the stored chapter number in sync with the current `chapterNumber()`
    // input while in number mode, so deleting/reordering chapters reindexes the
    // generated image (e.g. `[chapterNumber]="$index + 1"`).
    effect(() => {
      const current = this.chapterNumber();
      untracked(() => {
        if (current == null || this.value()?.mode !== 'number') return;
        this.value.update((v) =>
          v?.mode === 'number' && v.chapterNumber !== current
            ? { ...v, chapterNumber: current }
            : v,
        );
      });
    });
    this.destroyRef.onDestroy(() => this.revokePreview());
  }

  onModeChange(next: NodeImageMode | null | undefined): void {
    if (!next || next === this.mode()) return;
    this.typeError.set(null);
    this.dimensionError.set(null);
    this.convertError.set(null);
    if (next === 'number') {
      this.selectChapterNumber();
      return;
    }
    this.value.update((v) => ({
      mode: next,
      iconId: v?.iconId ?? null,
      file: v?.file ?? null,
      chapterNumber: v?.chapterNumber ?? null,
    }));
  }

  onSearchChange(query: string): void {
    this.searchQuery.set(query);
  }

  selectIcon(iconId: string): void {
    this.value.update((v) => ({
      mode: v?.mode ?? 'icon',
      iconId,
      file: v?.file ?? null,
      chapterNumber: v?.chapterNumber ?? null,
    }));
  }

  selectChapterNumber(chapterNumber = this.chapterNumber()): void {
    if (chapterNumber == null) return;
    // Preserve the previous icon/file selection so switching back to icon/image
    // mode does not lose what the user had already picked (see docstring: the
    // value holds both fields so switching modes does not lose the selection).
    this.value.update((v) => ({
      mode: 'number',
      iconId: v?.iconId ?? null,
      file: v?.file ?? null,
      chapterNumber,
    }));
  }

  iconPreviewSrc(iconId: string): string {
    return `${this.imagesUrl}/preview?iconId=${encodeURIComponent(iconId)}`;
  }

  chapterNumberPreviewSrc(chapterNumber: number): string {
    return `${this.imagesUrl}/preview?chapterNumber=${encodeURIComponent(chapterNumber)}`;
  }

  onFileSelected(file: File | null): void {
    this.typeError.set(null);
    this.dimensionError.set(null);
    this.convertError.set(null);
    if (!file) {
      this.value.update((v) => ({
        mode: v?.mode ?? 'image',
        iconId: v?.iconId ?? null,
        file: null,
        chapterNumber: v?.chapterNumber ?? null,
      }));
      return;
    }
    if (this.isSvg(file)) {
      void this.convertSvgAndSet(file);
      return;
    }
    if (!this.isImage(file)) {
      this.typeError.set('PNG, JPEG or SVG only.');
      return;
    }
    void this.validateAndSet(file);
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const file = event.dataTransfer?.files?.[0] ?? null;
    if (!file) return;
    this.onFileSelected(file);
  }

  clearFile(): void {
    this.onFileSelected(null);
  }

  private async validateAndSet(file: File): Promise<void> {
    this.validating.set(true);
    try {
      const ok = await this.checkDimensions(file);
      if (!ok) {
        this.dimensionError.set(
          `Image must be ${this.expectedWidth()}\u00d7${this.expectedHeight()} px.`,
        );
        return;
      }
      this.value.update((v) => ({
        mode: v?.mode ?? 'image',
        iconId: v?.iconId ?? null,
        file,
        chapterNumber: v?.chapterNumber ?? null,
      }));
    } finally {
      this.validating.set(false);
    }
  }

  private async convertSvgAndSet(file: File): Promise<void> {
    this.converting.set(true);
    this.convertError.set(null);
    try {
      const blob = await this.packs.renderSvg(file);
      // Strip any trailing extension (case-insensitive) so an SVG detected only by
      // mime ("photo.svg") or a case variant ("cat.SVG") yields one clean "*.png".
      const base = file.name.replace(/\.[^.]+$/, '');
      const png = new File([blob], `${base}.png`, {
        type: blob.type || 'image/png',
      });
      this.value.update((v) => ({
        mode: v?.mode ?? 'image',
        iconId: v?.iconId ?? null,
        file: png,
        chapterNumber: v?.chapterNumber ?? null,
      }));
    } catch {
      this.convertError.set('Could not convert this SVG. Please try another file.');
    } finally {
      this.converting.set(false);
    }
  }

  private async checkDimensions(file: File): Promise<boolean> {
    const url = URL.createObjectURL(file);
    try {
      return await new Promise<boolean>((resolve) => {
        const img = new Image();
        img.onload = () =>
          resolve(
            img.naturalWidth === this.expectedWidth() &&
              img.naturalHeight === this.expectedHeight(),
          );
        img.onerror = () => resolve(false);
        img.src = url;
      });
    } finally {
      URL.revokeObjectURL(url);
    }
  }

  private isImage(file: File): boolean {
    return file.type === 'image/png' || file.type === 'image/jpeg';
  }

  private isSvg(file: File): boolean {
    return file.type === 'image/svg+xml' || /\.svg$/i.test(file.name);
  }

  private setPreview(file: File | null): void {
    if (this.previewedFile === file) return;
    this.revokePreview();
    this.previewedFile = file;
    this.previewUrl.set(file ? URL.createObjectURL(file) : null);
  }

  private revokePreview(): void {
    const url = this.previewUrl();
    if (url) URL.revokeObjectURL(url);
    this.previewUrl.set(null);
    this.previewedFile = null;
  }
}
