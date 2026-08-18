export interface Settings {
  libraryPath: string;
  unofficialDbPath: string | null;
  targetDeviceType: string | null;
  ttsProvider: string | null;
  ttsOpenAiApiKey: string | null;
  ttsElevenLabsApiKey: string | null;
  ttsVoice: string | null;
  ttsLang: string | null;
}

export interface TtsVoice {
  id: string;
  name: string;
}

export interface TtsVoicesResponse {
  provider: string;
  voices: TtsVoice[];
  fallback: boolean;
}

export interface ApiStatusResponse {
  ok: boolean;
  message: string | null;
  error: string | null;
}
