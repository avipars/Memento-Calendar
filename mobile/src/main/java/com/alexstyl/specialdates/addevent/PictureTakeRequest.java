package com.alexstyl.specialdates.addevent;

import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;

final class PictureTakeRequest implements ImagePickIntent {

    @Override
    public Intent createIntentWithOutput(Uri outputUri) {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, outputUri);
        takePictureIntent.addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return takePictureIntent;
    }
}
