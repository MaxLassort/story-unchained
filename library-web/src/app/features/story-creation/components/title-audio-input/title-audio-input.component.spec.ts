import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { TitleAudioInputComponent } from './title-audio-input.component';

class FakeMediaRecorder {
  static isTypeSupported = vi.fn(() => true);
  mimeType = 'audio/webm';
  state: RecordingState = 'inactive';
  ondataavailable: ((event: BlobEvent) => void) | null = null;
  onstop: (() => void) | null = null;

  constructor(
    public readonly stream: MediaStream,
    public readonly options?: MediaRecorderOptions,
  ) {}

  start(): void {
    this.state = 'recording';
  }

  stop(): void {
    this.state = 'inactive';
    const chunk = new Blob(['recorded-audio'], { type: 'audio/webm' });
    this.ondataavailable?.({ data: chunk } as BlobEvent);
    this.onstop?.();
  }
}

/** Minimal fake AudioBuffer for WAV encoding in tests. */
function fakeAudioBuffer(length = 100): AudioBuffer {
  return {
    length,
    sampleRate: 44100,
    numberOfChannels: 1,
    duration: length / 44100,
    getChannelData: () => new Float32Array(length),
    copyFromChannel: vi.fn(),
    copyToChannel: vi.fn(),
  } as unknown as AudioBuffer;
}

class FakeAudioContext {
  decodeAudioData = vi.fn().mockResolvedValue(fakeAudioBuffer());
  close = vi.fn().mockResolvedValue(undefined);
}

describe('TitleAudioInputComponent', () => {
  let getUserMediaMock: ReturnType<typeof vi.fn>;
  let enumerateDevicesMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    getUserMediaMock = vi.fn().mockResolvedValue({
      getTracks: () => [{ stop: vi.fn() }],
      getAudioTracks: () => [{ muted: false, stop: vi.fn(), onmute: null, onunmute: null }],
    });
    enumerateDevicesMock = vi.fn().mockResolvedValue([]);
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia: getUserMediaMock, enumerateDevices: enumerateDevicesMock },
      configurable: true,
    });
    vi.stubGlobal('MediaRecorder', FakeMediaRecorder);
    vi.stubGlobal('AudioContext', FakeAudioContext);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  async function createComponent() {
    await TestBed.configureTestingModule({
      imports: [TitleAudioInputComponent],
      providers: [provideHttpClient()],
    }).compileComponents();
    const fixture = TestBed.createComponent(TitleAudioInputComponent);
    fixture.componentInstance.value.set({ mode: 'text', text: '', file: null });
    fixture.detectChanges();
    return fixture;
  }

  it('defaults to text mode and tracks the text in the model value', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.onTextChange('  The little dragon  ');

    expect(component.value()).toEqual({
      mode: 'text',
      text: '  The little dragon  ',
      file: null,
    });
  });

  it('tracks the uploaded file when switching to audio mode', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    const file = new File(['x'], 'titre.mp3', { type: 'audio/mpeg' });
    component.onModeChange('audio');
    component.onFileSelected(file);

    expect(component.value()).toEqual({ mode: 'audio', text: '', file });
    expect(component.hasAudio()).toBe(true);
    expect(component.audioUrl()).not.toBeNull();
  });

  it('keeps both fields when switching modes and clears the file on demand', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.onTextChange('Titre');
    component.onModeChange('audio');
    component.onFileSelected(new File(['x'], 'titre.mp3', { type: 'audio/mpeg' }));
    component.onModeChange('text');
    expect(component.value()?.text).toBe('Titre');
    expect(component.value()?.file).not.toBeNull();

    component.clearFile();
    expect(component.value()?.file).toBeNull();
    expect(component.hasAudio()).toBe(false);
  });

  it('records the microphone and stores the result as a regular audio file', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.onModeChange('record');
    fixture.detectChanges();
    expect(component.recording()).toBe(false);

    await component.startRecording();
    fixture.detectChanges();
    expect(component.recording()).toBe(true);
    expect(getUserMediaMock).toHaveBeenCalledWith({ audio: true });

    component.stopRecording();
    await fixture.whenStable(); // wait for async finishRecording to complete
    fixture.detectChanges();

    expect(component.recording()).toBe(false);
    expect(component.value()?.mode).toBe('audio');
    expect(component.value()?.file).toBeInstanceOf(File);
    expect(component.value()?.file?.name).toBe('recording.wav');
    expect(component.value()?.file?.type).toBe('audio/wav');
    expect(component.hasAudio()).toBe(true);
  });

  it('shows an error and keeps the previous file when the microphone is denied', async () => {
    getUserMediaMock.mockRejectedValue(new DOMException('Permission denied', 'NotAllowedError'));
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.onFileSelected(new File(['x'], 'titre.mp3', { type: 'audio/mpeg' }));
    component.onModeChange('record');
    await component.startRecording();
    fixture.detectChanges();

    expect(component.recording()).toBe(false);
    expect(component.recordError()).toContain('permission');
    expect(component.value()?.file).not.toBeNull();
  });

  it('cancels an ongoing recording when switching modes', async () => {
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.onModeChange('record');
    await component.startRecording();
    expect(component.recording()).toBe(true);

    component.onModeChange('text');
    fixture.detectChanges();

    expect(component.recording()).toBe(false);
    expect(component.value()?.mode).toBe('text');
    expect(component.value()?.file).toBeNull();
  });

  it('always displays the microphone selection in record mode before recording', async () => {
    enumerateDevicesMock.mockResolvedValue([
      { kind: 'audioinput', deviceId: 'mic-1', label: 'Microphone 1' },
    ]);
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.onModeChange('record');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const select = fixture.nativeElement.querySelector('.title-audio__device');
    expect(select).not.toBeNull();
    expect(component.recording()).toBe(false);
  });

  it('uses the selected microphone device when one is chosen', async () => {
    enumerateDevicesMock.mockResolvedValue([
      { kind: 'audioinput', deviceId: 'mic-1', label: 'Built-in Microphone' },
      { kind: 'audioinput', deviceId: 'mic-2', label: 'Headset' },
      { kind: 'audiooutput', deviceId: 'spk-1', label: 'Speakers' },
    ]);
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.onModeChange('record');
    component.onDeviceChange('mic-2');
    await component.startRecording();

    expect(getUserMediaMock).toHaveBeenCalledWith({
      audio: { deviceId: { exact: 'mic-2' } },
    });
    expect(component.devices().length).toBe(2);
    expect(component.devices()[1].label).toBe('Headset');
  });

  it('falls back to the system default and warns when no microphone is delivered', async () => {
    getUserMediaMock.mockResolvedValue({
      getTracks: () => [{ stop: vi.fn() }],
      getAudioTracks: () => [{ muted: true, stop: vi.fn(), onmute: null, onunmute: null }],
    });
    const fixture = await createComponent();
    const component = fixture.componentInstance;

    component.onModeChange('record');
    await component.startRecording();

    expect(getUserMediaMock).toHaveBeenCalledWith({ audio: true });
    expect(component.trackMuted()).toBe(true);
  });
});