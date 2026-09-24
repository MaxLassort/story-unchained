declare module 'imagetracerjs' {
  export interface ImageTracerPaletteColor {
    r: number;
    g: number;
    b: number;
    a: number;
  }

  export interface ImageTracerOptions {
    ltres?: number;
    qtres?: number;
    pathomit?: number;
    rightangleenhance?: boolean;
    colorsampling?: number;
    numberofcolors?: number;
    mincolorratio?: number;
    colorquantcycles?: number;
    layering?: number | string;
    strokewidth?: number;
    linefilter?: boolean;
    scale?: number;
    roundcoords?: number;
    viewbox?: boolean;
    desc?: boolean;
    blurradius?: number;
    blurdelta?: number;
    pal?: ImageTracerPaletteColor[];
  }

  export interface ImageTracerImageData {
    width: number;
    height: number;
    data: Uint8ClampedArray | number[] | Buffer;
  }

  export interface ImageTracerApi {
    imagedataToSVG(imgd: ImageTracerImageData, options?: ImageTracerOptions | string): string;
    imageToSVG(
      url: string,
      callback: (svg: string) => void,
      options?: ImageTracerOptions | string,
    ): void;
  }

  const ImageTracer: ImageTracerApi;
  export default ImageTracer;
}
