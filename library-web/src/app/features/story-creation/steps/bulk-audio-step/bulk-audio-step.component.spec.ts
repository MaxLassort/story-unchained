import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { BulkAudioStepComponent } from './bulk-audio-step.component';
import { ChaptersEditorState } from '../../chapters-editor-state.service';

describe('BulkAudioStepComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BulkAudioStepComponent],
      providers: [ChaptersEditorState],
    }).compileComponents();
  });

  function createComponent() {
    const fixture = TestBed.createComponent(BulkAudioStepComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('starts with no staged chapters but is skippable (complete)', () => {
    const fixture = createComponent();
    expect(fixture.componentInstance.chapters().length).toBe(0);
    expect(fixture.componentInstance.complete()).toBe(true);
  });

  it('stages one pre-filled chapter per dropped audio file with the narration set', async () => {
    const fixture = createComponent();
    const file = new File(['audio'], 'intro.mp3', { type: 'audio/mpeg' });
    await fixture.componentInstance.addFiles([file]);
    fixture.detectChanges();

    const ch = fixture.componentInstance.chapters()[0];
    expect(ch.name).toBe('Chapitre 1');
    expect(ch.narrationFile).toBe(file);
    // No TTS fallback: the title audio stays empty until the asset pool provides a file.
    expect(ch.titleAudio).toBeNull();
    expect(ch.image).toEqual({ mode: 'number', iconId: null, file: null, chapterNumber: 1 });
    expect(fixture.componentInstance.chapters().length).toBe(1);
    expect(fixture.componentInstance.complete()).toBe(true);
  });

  it('uses the uploaded TTS asset as mode audio when the pool is loaded', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    const titleAudio = new File(['x'], 'chapter-1.mp3', { type: 'audio/mpeg' });
    c.titleAudioPool.set(new Map([[1, titleAudio]]));
    await c.addFiles([new File(['a'], 'a.mp3', { type: 'audio/mpeg' })]);
    fixture.detectChanges();

    expect(c.chapters()[0].titleAudio?.mode).toBe('audio');
    expect(c.chapters()[0].titleAudio?.file).toBe(titleAudio);
  });

  it('continues chapter numbering across batches', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    await c.addFiles([
      new File(['a'], 'a.mp3', { type: 'audio/mpeg' }),
      new File(['b'], 'b.mp3', { type: 'audio/mpeg' }),
    ]);
    await c.addFiles([new File(['c'], 'c.mp3', { type: 'audio/mpeg' })]);
    fixture.detectChanges();

    expect(c.chapters().length).toBe(3);
    expect(c.chapters()[0].name).toBe('Chapitre 1');
    expect(c.chapters()[1].name).toBe('Chapitre 2');
    expect(c.chapters()[2].name).toBe('Chapitre 3');
  });

  it('removes a staged chapter', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    await c.addFiles([new File(['a'], 'a.mp3', { type: 'audio/mpeg' })]);
    await c.addFiles([new File(['b'], 'b.mp3', { type: 'audio/mpeg' })]);
    fixture.detectChanges();
    expect(c.chapters().length).toBe(2);

    c.removeChapter(0);
    fixture.detectChanges();
    expect(c.chapters().length).toBe(1);
    expect(c.chapters()[0].name).toBe('Chapitre 2');
  });

  it('rejects non-audio and oversized files', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    await c.addFiles([new File(['x'], 'note.txt', { type: 'text/plain' })]);
    fixture.detectChanges();
    expect(c.chapters().length).toBe(0);
    expect(c.typeError()).toContain('Only audio files up to 50 MB');
  });

  it('renames a staged chapter', async () => {
    const fixture = createComponent();
    const c = fixture.componentInstance;
    await c.addFiles([new File(['a'], 'a.mp3', { type: 'audio/mpeg' })]);
    fixture.detectChanges();
    c.renameChapter(0, 'Ma première aventure');
    fixture.detectChanges();
    expect(c.chapters()[0].name).toBe('Ma première aventure');
  });
});
