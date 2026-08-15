import { Component, DOCUMENT, ElementRef, OnDestroy, OnInit, PLATFORM_ID, Renderer2, inject, input } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

@Component({
  selector: 'app-loading-overlay',
  imports: [MatProgressSpinnerModule],
  templateUrl: './loading-overlay.component.html',
  styleUrl: './loading-overlay.component.scss',
})
export class LoadingOverlayComponent implements OnInit, OnDestroy {
  readonly visible = input(false);
  readonly label = input('');
  readonly fullscreen = input(true);

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly renderer = inject(Renderer2);
  private readonly doc = inject(DOCUMENT);
  private readonly platformId = inject(PLATFORM_ID);
  private moved = false;

  ngOnInit(): void {
    if (this.fullscreen() && isPlatformBrowser(this.platformId)) {
      this.renderer.appendChild(this.doc.body, this.host.nativeElement);
      this.moved = true;
    }
  }

  ngOnDestroy(): void {
    if (this.moved && this.host.nativeElement.parentNode === this.doc.body) {
      this.renderer.removeChild(this.doc.body, this.host.nativeElement);
    }
  }
}