import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../../../core/pipes/translate.pipe';

@Component({
  selector: 'app-create-story-button',
  imports: [MatButtonModule, MatIconModule, RouterLink, TranslatePipe],
  templateUrl: './create-story-button.component.html',
  styleUrl: './create-story-button.component.scss',
})
export class CreateStoryButtonComponent {}