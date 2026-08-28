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

export interface SyncJobStartResponse {
  jobId: number;
  status: string;
}

export interface SyncJobStatusResponse {
  jobId: number;
  status: string;
  totalEntries: number;
  processedEntries: number;
  synchronizedCount: number;
  invalidQueuedCount: number;
  failedCount: number;
  message: string | null;
  startedAtEpochMs: number;
  finishedAtEpochMs: number | null;
  batchSize: number;
  parallelism: number;
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
  chapters: StoryChapterDraftSummary[];
}

export interface UpdateDraftRequest {
  title?: string;
  description?: string;
}

export interface ChapterIconDto {
  id: string;
  name: string;
}

export interface ChapterIconsResponse {
  icons: ChapterIconDto[];
}

export type NodeImageMode = 'icon' | 'image';

export interface NodeImageSelection {
  mode: NodeImageMode;
  iconId: string | null;
  file: File | null;
}
