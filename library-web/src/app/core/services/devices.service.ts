import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpContext } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { DeviceInfos, DevicePack, CopyPackRequest, CopyPackResponse, DeviceSnapshot } from '../models';
import { SKIP_ERROR_SNACKBAR } from './http-context';

import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class DevicesService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/devices`;
  private readonly silentContext = new HttpContext().set(SKIP_ERROR_SNACKBAR, true);

  readonly deviceInfos = signal<DeviceInfos>({ plugged: false, uuid: null, serial: null, firmware: null, driver: null, storage: null, error: false });
  readonly devicePacks = signal<DevicePack[]>([]);
  readonly snapshots = signal<DeviceSnapshot[]>([]);
  readonly loadingSnapshots = signal(false);

  async refreshDeviceInfos(): Promise<void> {
    try {
      const infos = await firstValueFrom(
        this.http.get<DeviceInfos>(this.baseUrl, { context: this.silentContext }),
      );
      this.deviceInfos.set(infos);
    } catch {
      this.deviceInfos.set({ plugged: false, uuid: null, serial: null, firmware: null, driver: null, storage: null, error: false });
    }
  }

  async refreshDevicePacks(): Promise<void> {
    try {
      const packs = await firstValueFrom(
        this.http.get<DevicePack[]>(`${this.baseUrl}/packs`, { context: this.silentContext }),
      );
      this.devicePacks.set(packs);
    } catch {
      this.devicePacks.set([]);
    }
  }

  async refreshSnapshots(): Promise<void> {
    this.loadingSnapshots.set(true);
    try {
      const snaps = await firstValueFrom(
        this.http.get<DeviceSnapshot[]>(`${this.baseUrl}/snapshots`, { context: this.silentContext }),
      );
      this.snapshots.set(snaps);
    } catch {
      this.snapshots.set([]);
    } finally {
      this.loadingSnapshots.set(false);
    }
  }

  async copyToDevice(packId: string): Promise<CopyPackResponse> {
    const body: CopyPackRequest = { packId };
    return firstValueFrom(this.http.post<CopyPackResponse>(`${this.baseUrl}/packs`, body));
  }

  async deleteFromDevice(packId: string): Promise<CopyPackResponse> {
    return firstValueFrom(this.http.delete<CopyPackResponse>(`${this.baseUrl}/packs/${packId}`));
  }

  async copyToLibrary(packId: string): Promise<CopyPackResponse> {
    return firstValueFrom(this.http.post<CopyPackResponse>(`${this.baseUrl}/packs/${packId}/copy-to-library`, {}));
  }
}
