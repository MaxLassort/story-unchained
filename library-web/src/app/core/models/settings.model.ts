export interface Settings {
  libraryPath: string;
  targetDeviceType: string | null;
}

export interface ApiStatusResponse {
  ok: boolean;
  message: string | null;
  error: string | null;
}
