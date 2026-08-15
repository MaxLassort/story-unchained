export interface DeviceStorage {
  size: number;
  free: number;
  taken: number;
}

export interface DeviceInfos {
  plugged: boolean;
  uuid: string | null;
  serial: string | null;
  firmware: string | null;
  driver: string | null;
  storage: DeviceStorage | null;
  error: boolean;
}

export interface DevicePack {
  uuid: string;
  version: number;
  sizeInBytes: number;
  title: string | null;
  thumbnail: string | null;
  locale: string | null;
  ageMin: number | null;
  ageMax: number | null;
  durationMs: number | null;
  storyCount: number | null;
}

export interface ConversionEvent {
  packId: string;
  sourceFormat: string;
  targetFormat: string;
  status: string;
  message: string | null;
}

export interface DeviceEvent {
  device: DeviceInfos;
  packs: DevicePack[] | null;
  conversion: ConversionEvent | null;
}

export interface CopyPackRequest {
  packId: string;
}

export interface CopyPackResponse {
  ok: boolean;
  error: string | null;
  message: string | null;
}

export interface DeviceSnapshot {
  uuid: string;
  lastSeenAtEpochMs: number;
  packCount: number;
  packs: DevicePack[];
}
