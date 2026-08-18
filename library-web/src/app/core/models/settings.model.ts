export interface Settings {
  libraryPath: string;
  unofficialDbPath: string | null;
  targetDeviceType: string | null;
  ttsProvider: string | null;
  ttsApiKey: string | null;
  ttsVoice: string | null;
}

export interface ApiStatusResponse {
  ok: boolean;
  message: string | null;
  error: string | null;
}
