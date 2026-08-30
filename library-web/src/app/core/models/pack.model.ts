export type PackFormat = 'ARCHIVE' | 'RAW' | 'FS' | 'UNKNOWN';

export interface PackMetadata {
  title: string | null;
  description: string | null;
  thumbnail: string | null;
  version: number;
  factoryDisabled: boolean;
  nightModeAvailable: boolean;
  official: boolean;
  linkedOfficialPackId: string | null;
  locale: string | null;
  ageMin: number | null;
  ageMax: number | null;
  durationMs: number | null;
  storyCount: number | null;
}

export interface PackVariant {
  format: PackFormat;
  storagePath: string;
}

export interface Pack {
  id: string;
  metadata: PackMetadata;
  variants: PackVariant[];
}

export interface PagedPacksResponse {
  content: Pack[];
  totalCount: number;
  page: number;
  pageSize: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface UpdatePackMetadataRequest {
  title?: string | null;
  description?: string | null;
  linkedOfficialPackId?: string | null;
  locale?: string | null;
  ageMin?: number | null;
  ageMax?: number | null;
  durationMs?: number | null;
  storyCount?: number | null;
}

export interface PackConversionRequest {
  sourceFormat: PackFormat;
  targetFormat: PackFormat;
}

export interface PackConversionResponse {
  ok: boolean;
  packId: string;
  sourceFormat: string;
  targetFormat: string;
  outputPath?: string | null;
  error?: string | null;
}

export interface SyncStatusEvent {
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED';
  totalEntries?: number;
  processedEntries?: number;
  synchronizedCount?: number;
  invalidQueuedCount?: number;
  failedCount?: number;
  message?: string | null;
  startedAtEpochMs?: number;
  finishedAtEpochMs?: number;
  batchSize?: number;
  parallelism?: number;
}

export interface DraftCreatedResponse {
  draftId: string;
}

export interface StoryChapterDraftSummary {
  id: string;
  name: string;
  hasTitleAudio: boolean;
  titleAudioBytes: number;
  titleText: string | null;
  hasNarrationAudio: boolean;
  narrationAudioBytes: number;
  hasImage: boolean;
  imageBytes: number;
  iconId: string | null;
}

export interface StoryDraftSummary {
  id: string;
  title: string | null;
  description: string | null;
  hasThumbnail: boolean;
  thumbnailBytes: number;
  hasCover: boolean;
  coverBytes: number;
  hasTitleAudio: boolean;
  titleAudioBytes: number;
  titleText: string | null;
  hasMenuAudio: boolean;
  menuAudioBytes: number;
  menuText: string | null;
  chapters: StoryChapterDraftSummary[];
}

export interface UpdateDraftRequest {
  title?: string;
  description?: string;
}

/** Target of a consolidated draft file upload: pack root or a chapter. */
export interface DraftFileTarget {
  scope: 'pack' | 'chapter';
  chapterId?: string;
  field: 'titleAudio' | 'menuAudio' | 'thumbnail' | 'cover' | 'narration' | 'image';
}

/** Common patch payload for a draft node (pack root or chapter); only provided fields are applied. */
export interface PatchNodePayload {
  name?: string;
  titleText?: string;
  menuText?: string;
  iconId?: string;
}

export interface ChapterIconDto {
  id: string;
  name: string;
}

export interface ChapterIconsResponse {
  icons: ChapterIconDto[];
}

export type NodeImageMode = 'icon' | 'image' | 'number';

export interface NodeImageSelection {
  mode: NodeImageMode;
  iconId: string | null;
  file: File | null;
  /** Chapter number to render as the image (only used when `mode === 'number'`). */
  chapterNumber?: number | null;
}
