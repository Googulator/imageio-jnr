package de.digitalcollections.turbojpeg;

import de.digitalcollections.turbojpeg.lib.enums.*;
import de.digitalcollections.turbojpeg.lib.libturbojpeg;
import de.digitalcollections.turbojpeg.lib.structs.tjscalingfactor;
import de.digitalcollections.turbojpeg.lib.structs.tjtransform;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferShort;
import java.awt.image.DataBufferUShort;
import java.awt.image.Raster;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import jnr.ffi.LibraryLoader;
import jnr.ffi.Pointer;
import jnr.ffi.Runtime;
import jnr.ffi.Struct;
import jnr.ffi.byref.IntByReference;
import jnr.ffi.byref.NativeLongByReference;
import jnr.ffi.byref.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Java bindings for libturbojpeg via JFFI * */
public class TurboJpeg {

  private static final Logger LOG = LoggerFactory.getLogger(TurboJpeg.class);
  public libturbojpeg lib;
  public Runtime runtime;

  public TurboJpeg() {
    lib = LibraryLoader.create(libturbojpeg.class).load("turbojpeg");
    runtime = Runtime.getRuntime(lib);
  }

  /**
   * Return information about the JPEG image in the input buffer
   *
   * @param jpegData jpeg image data
   * @return information about the jpeg image
   * @throws de.digitalcollections.turbojpeg.TurboJpegException if decompressing header with library
   *     fails
   */
  public Info getInfo(byte[] jpegData) throws TurboJpegException {
    Pointer codec = null;
    try {
      codec = lib.tjInitDecompress();

      IntByReference width = new IntByReference();
      IntByReference height = new IntByReference();
      IntByReference jpegSubsamp = new IntByReference();
      IntByReference jpegColorspace = new IntByReference();
      int rv =
          lib.tjDecompressHeader3(
              codec,
              ByteBuffer.wrap(jpegData),
              jpegData.length,
              width,
              height,
              jpegSubsamp,
              jpegColorspace);
      if (rv != 0) {
        throw new TurboJpegException(lib.tjGetErrorStr());
      }

      IntByReference numRef = new IntByReference();
      Pointer factorPtr = lib.tjGetScalingFactors(numRef);
      final Integer numOfFactors = numRef.getValue();
      tjscalingfactor[] factors = new tjscalingfactor[numOfFactors];
      for (int i = 0; i < numOfFactors; i++) {
        tjscalingfactor f = new tjscalingfactor(runtime);
        factorPtr = factorPtr.slice(Struct.size(f));
        f.useMemory(factorPtr);
        factors[i] = f;
      }
      return new Info(
          width.getValue(),
          height.getValue(),
          jpegSubsamp.getValue(),
          jpegColorspace.getValue(),
          factors);
    } finally {
      if (codec != null && codec.address() != 0) {
        lib.tjDestroy(codec);
      }
    }
  }

  /**
   * Decode the JPEG image in the input buffer into a BufferedImage.
   *
   * @param jpegData JPEG data input buffer
   * @param info Information about the JPEG image in the buffer
   * @param size Target decompressed dimensions, must be among the available sizes (see {@link
   *     Info#getAvailableSizes()})
   * @return The decoded image
   * @throws TurboJpegException if decompression with library fails
   */
  public BufferedImage decode(byte[] jpegData, Info info, Dimension size)
      throws TurboJpegException {
    Pointer codec = null;
    try {
      codec = lib.tjInitDecompress();
      int width = info.getWidth();
      int height = info.getHeight();
      if (size != null) {
        if (!info.getAvailableSizes().contains(size)) {
          throw new IllegalArgumentException(
              String.format("Invalid size, must be one of %s", info.getAvailableSizes()));
        } else {
          width = size.width;
          height = size.height;
        }
      }
      boolean isGray = info.getSubsampling() == TJSAMP.TJSAMP_GRAY;
      int imgType;
      if (isGray) {
        imgType = BufferedImage.TYPE_BYTE_GRAY;
      } else {
        imgType = BufferedImage.TYPE_3BYTE_BGR;
      }
      BufferedImage img = new BufferedImage(width, height, imgType);
      // Wrap the underlying data buffer of the image with a ByteBuffer, so we can pass it over the
      // ABI
      ByteBuffer outBuf = asByteBuffer(img.getRaster().getDataBuffer());
      int rv =
          lib.tjDecompress2(
              codec,
              ByteBuffer.wrap(jpegData),
              jpegData.length,
              outBuf,
              width,
              isGray ? width : width * 3,
              height,
              isGray ? TJPF.TJPF_GRAY : TJPF.TJPF_BGR,
              0);
      if (rv != 0) {
        TJERR errorCode = TJERR.fromInt(lib.tjGetErrorCode(codec));
        String errorMessage = lib.tjGetErrorStr();
        if (errorCode == TJERR.TJERR_FATAL) {
          LOG.error(
              "Could not decompress JPEG (dimensions: {}x{}, gray: {})", width, height, isGray);
          throw new TurboJpegException(errorMessage);
        }
        LOG.warn(
            "Could not decompress JPEG (dimensions: {}x{}, gray: {}, message: {})",
            width,
            height,
            isGray,
            errorMessage);
      }
      return img;
    } finally {
      if (codec != null && codec.address() != 0) {
        lib.tjDestroy(codec);
      }
    }
  }

  /**
   * Encode an image to JPEG
   *
   * @param img image as rectangle of pixels
   * @param quality compression quality
   * @return jpeg image
   * @throws de.digitalcollections.turbojpeg.TurboJpegException if compression with library fails
   */
  public ByteBuffer encode(Raster img, int quality) throws TurboJpegException {
    Pointer codec = null;
    PointerByReference bufPtrRef = null;
    try {
      TJPF pixelFmt;
      switch (img.getNumBands()) {
        case 4:
          pixelFmt = TJPF.TJPF_BGRX; // 4BYTE_BGRA
          break;
        case 3:
          pixelFmt = TJPF.TJPF_BGR; // 3BYTE_BGR
          break;
        case 1:
          pixelFmt = TJPF.TJPF_GRAY; // 1BYTE_GRAY
          break;
        default:
          throw new IllegalArgumentException("Illegal sample format");
      }
      // TODO: Make sampling format configurable
      TJSAMP sampling = pixelFmt == TJPF.TJPF_GRAY ? TJSAMP.TJSAMP_GRAY : TJSAMP.TJSAMP_420;
      codec = lib.tjInitCompress();

      // Allocate JPEG target buffer
      int bufSize = (int) lib.tjBufSize(img.getWidth(), img.getHeight(), sampling);
      Pointer bufPtr = lib.tjAlloc(bufSize);
      bufPtrRef = new PointerByReference(bufPtr);
      NativeLongByReference lenPtr = new NativeLongByReference(bufSize);

      // Wrap source image data buffer with ByteBuffer to pass it over the ABI
      ByteBuffer inBuf;
      if (img.getNumBands() == 1 && img.getSampleModel().getSampleSize(0) == 1) {
        // For binary images, we need to convert our (0, 1) binary values into (0, 255) greyscale
        // values
        int[] buf = new int[img.getWidth() * img.getHeight()];
        img.getPixels(0, 0, img.getWidth(), img.getHeight(), buf);
        byte[] byteBuf = new byte[buf.length];
        for (int i = 0; i < buf.length; i++) {
          byteBuf[i] = (byte) (buf[i] == 0 ? 0x00 : 0xFF);
        }
        inBuf = ByteBuffer.wrap(byteBuf).order(runtime.byteOrder());
      } else {
        inBuf = asByteBuffer(img.getDataBuffer());
      }
      int rv =
          lib.tjCompress2(
              codec,
              inBuf,
              img.getWidth(),
              0,
              img.getHeight(),
              pixelFmt,
              bufPtrRef,
              lenPtr,
              sampling,
              quality,
              0);
      if (rv != 0) {
        LOG.error(
            "Could not compress image (dimensions: {}x{}, format: {}, sampling: {}, quality: {}",
            img.getWidth(),
            img.getHeight(),
            pixelFmt,
            sampling,
            quality);
        throw new TurboJpegException(lib.tjGetErrorStr());
      }
      ByteBuffer outBuf =
          ByteBuffer.allocate(lenPtr.getValue().intValue()).order(runtime.byteOrder());
      bufPtrRef.getValue().get(0, outBuf.array(), 0, lenPtr.getValue().intValue());
      ((Buffer) outBuf).rewind();
      return outBuf;
    } finally {
      if (codec != null && codec.address() != 0) {
        lib.tjDestroy(codec);
      }
      if (bufPtrRef != null
          && bufPtrRef.getValue() != null
          && bufPtrRef.getValue().address() != 0) {
        lib.tjFree(bufPtrRef.getValue());
      }
    }
  }

  /**
   * Transform a JPEG image without decoding it fully
   *
   * @param jpegData JPEG input buffer
   * @param info Information about the JPEG (from {@link #getInfo(byte[])}
   * @param region Source region to crop out of JPEG
   * @param rotation Degrees to rotate the JPEG, must be 90, 180 or 270
   * @return The transformed JPEG data
   * @throws TurboJpegException if image transformation fails
   */
  public ByteBuffer transform(byte[] jpegData, Info info, Rectangle region, int rotation)
      throws TurboJpegException {
    Pointer codec = null;
    PointerByReference bufPtrRef = null;
    try {
      codec = lib.tjInitTransform();
      tjtransform transform = new tjtransform(runtime);

      int width = info.getWidth();
      int height = info.getHeight();
      boolean flipCoords = rotation == 90 || rotation == 270;
      if (region != null) {
        Dimension mcuSize = info.getMCUSize();
        if (((region.x + region.width) != width && region.width % mcuSize.width != 0)
            || ((region.y + region.height) != height && region.height % mcuSize.height != 0)) {
          throw new IllegalArgumentException(
              String.format(
                  "Invalid cropping region %d×%d, width must be divisible by %d, height by %d",
                  region.width, region.height, mcuSize.width, mcuSize.height));
        }
        transform.options.set(TJXOPT.TJXOPT_CROP | TJXOPT.TJXOPT_TRIM);
        transform.r.x.set(region.x);
        transform.r.y.set(region.y);
        // If any cropping dimension equals the original dimension, libturbojpeg requires it to be
        // set to 0
        if ((region.x + region.width) >= (flipCoords ? info.getHeight() : info.getWidth())) {
          transform.r.w.set(0);
        } else {
          transform.r.w.set(region.width);
        }
        if ((region.y + region.height) >= (flipCoords ? info.getWidth() : info.getHeight())) {
          transform.r.h.set(0);
        } else {
          transform.r.h.set(region.height);
        }
      }
      if (rotation != 0) {
        TJXOP op;
        switch (rotation) {
          case 90:
            op = TJXOP.TJXOP_ROT90;
            break;
          case 180:
            op = TJXOP.TJXOP_ROT180;
            break;
          case 270:
            op = TJXOP.TJXOP_ROT270;
            break;
          default:
            throw new IllegalArgumentException("Invalid rotation, must be 90, 180 or 270");
        }
        transform.op.set(op.intValue());
      }
      Buffer inBuf = ByteBuffer.wrap(jpegData).order(runtime.byteOrder());
      NativeLongByReference lenRef = new NativeLongByReference();
      bufPtrRef = new PointerByReference();
      int rv = lib.tjTransform(codec, inBuf, jpegData.length, 1, bufPtrRef, lenRef, transform, 0);
      if (rv != 0) {
        TJERR errorCode = TJERR.fromInt(lib.tjGetErrorCode(codec));
        String errorMessage = lib.tjGetErrorStr();
        if (errorCode == TJERR.TJERR_FATAL) {
          LOG.error(
              "Could not compress image (crop: {},{},{},{}, rotate: {})",
              transform.r.x,
              transform.r.y,
              transform.r.w,
              transform.r.h,
              rotation);
          throw new TurboJpegException(errorMessage);
        }
        LOG.warn(
            "Could not compress image (crop: {},{},{},{}, rotate: {}, message: {})",
            transform.r.x,
            transform.r.y,
            transform.r.w,
            transform.r.h,
            rotation,
            errorMessage);
      }
      ByteBuffer outBuf =
          ByteBuffer.allocate(lenRef.getValue().intValue()).order(runtime.byteOrder());
      bufPtrRef.getValue().get(0, outBuf.array(), 0, lenRef.getValue().intValue());
      ((Buffer) outBuf).rewind();
      return outBuf;
    } finally {
      if (codec != null && codec.address() != 0) {
        lib.tjDestroy(codec);
      }
      if (bufPtrRef != null
          && bufPtrRef.getValue() != null
          && bufPtrRef.getValue().address() != 0) {
        lib.tjFree(bufPtrRef.getValue());
      }
    }
  }

  private ByteBuffer asByteBuffer(DataBuffer dataBuffer) {
    ByteBuffer byteBuffer;
    if (dataBuffer instanceof DataBufferByte) {
      byte[] pixelData = ((DataBufferByte) dataBuffer).getData();
      byteBuffer = ByteBuffer.wrap(pixelData).order(runtime.byteOrder());
    } else if (dataBuffer instanceof DataBufferUShort) {
      short[] pixelData = ((DataBufferUShort) dataBuffer).getData();
      byteBuffer = ByteBuffer.allocate(pixelData.length * 2).order(runtime.byteOrder());
      byteBuffer.asShortBuffer().put(ShortBuffer.wrap(pixelData));
    } else if (dataBuffer instanceof DataBufferShort) {
      short[] pixelData = ((DataBufferShort) dataBuffer).getData();
      byteBuffer = ByteBuffer.allocate(pixelData.length * 2).order(runtime.byteOrder());
      byteBuffer.asShortBuffer().put(ShortBuffer.wrap(pixelData));
    } else if (dataBuffer instanceof DataBufferInt) {
      int[] pixelData = ((DataBufferInt) dataBuffer).getData();
      byteBuffer = ByteBuffer.allocate(pixelData.length * 4).order(runtime.byteOrder());
      byteBuffer.asIntBuffer().put(IntBuffer.wrap(pixelData));
    } else {
      throw new IllegalArgumentException("Unsupported DataBuffer type: " + dataBuffer.getClass());
    }
    return byteBuffer;
  }
}
