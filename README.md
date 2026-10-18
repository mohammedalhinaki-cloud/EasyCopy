# Easy Copy

Android app to copy accessible on-screen text using AccessibilityService. No OCR or Internet required.

APK is built by GitHub Actions; see Actions > Build Easy Copy APK > Artifacts.

## Android OCR
Screenshots shared to Easy Copy are recognized entirely on device. Arabic and English are supported by Tesseract 5 (best-quality official Arabic and English trained data), with Google's bundled ML Kit Latin text reader as an English alternative.

The build workflow downloads both Tesseract trained-data files into `app/src/main/assets/tessdata`. To build manually, download `ara.traineddata` and `eng.traineddata` from [tesseract-ocr/tessdata_best](https://github.com/tesseract-ocr/tessdata_best) into that folder first. They are bundled within the APK; no download is needed at runtime.

The application icon uses the existing copy vector (`app/src/main/res/drawable/ic_copy.xml`) on a dark blue background.
