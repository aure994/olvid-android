/*
 *  Olvid for Android
 *  Copyright © 2019-2021 Olvid SAS
 *
 *  This file is part of Olvid for Android.
 *
 *  Olvid is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License, version 3,
 *  as published by the Free Software Foundation.
 *
 *  Olvid is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with Olvid.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.olvid.messenger.owneddetails;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.olvid.engine.Logger;
import io.olvid.messenger.App;
import io.olvid.messenger.BuildConfig;
import io.olvid.messenger.R;
import io.olvid.messenger.settings.SettingsActivity;
import io.olvid.messenger.customClasses.InitialView;


public class OwnedGroupDetailsFragment extends Fragment {
    private static final int REQUEST_CODE_PERMISSION_CAMERA = 8511;

    private static final int REQUEST_CODE_CHOOSE_IMAGE = 8596;
    private static final int REQUEST_CODE_TAKE_PICTURE = 8597;
    private static final int REQUEST_CODE_SELECT_ZONE = 8598;

    private OwnedGroupDetailsViewModel viewModel;

    private TextInputLayout groupNameLayout;
    private EditText groupNameEditText;
    private EditText groupDescriptionEditText;
    private InitialView initialView;
    private ImageView cameraIcon;

    private boolean useDialogBackground = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_group_details, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(requireActivity()).get(OwnedGroupDetailsViewModel.class);

        groupNameLayout = view.findViewById(R.id.group_details_group_name_layout);
        groupNameEditText = view.findViewById(R.id.group_details_group_name);
        groupDescriptionEditText = view.findViewById(R.id.group_details_group_description);
        cameraIcon = view.findViewById(R.id.camera_icon);
        if (useDialogBackground) {
            cameraIcon.setImageResource(R.drawable.ic_camera_bordered_dialog);
        }
        initialView = view.findViewById(R.id.group_details_initial_view);

        if (SettingsActivity.useKeyboardIncognitoMode()) {
            groupNameEditText.setImeOptions(groupNameEditText.getImeOptions() | EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING);
            groupDescriptionEditText.setImeOptions(groupDescriptionEditText.getImeOptions() | EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING);
        }

        viewModel.getValid().observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            boolean first = true;

            @Override
            public void onChanged(Boolean valid) {
                if (first) {
                    first = false;
                    return;
                }
                if (valid == null || !valid) {
                    groupNameLayout.setError(getString(R.string.message_error_group_name_empty));
                } else {
                    groupNameLayout.setError(null);
                }
            }
        });

        groupNameEditText.setText(viewModel.getGroupName());
        groupNameEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
            @Override
            public void afterTextChanged(Editable s) {
                viewModel.setGroupName(s.toString());
            }
        });

        groupDescriptionEditText.setText(viewModel.getGroupDescription());
        groupDescriptionEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
            @Override
            public void afterTextChanged(Editable s) {
                viewModel.setGroupDescription(s.toString());
            }
        });

        viewModel.getInitialViewContent().observe(getViewLifecycleOwner(), initialViewContent -> {
            if (initialViewContent == null) {
                return;
            }
            if (initialViewContent.absolutePhotoUrl != null) {
                initialView.setAbsolutePhotoUrl(initialViewContent.bytesGroupOwnerAndUid, initialViewContent.absolutePhotoUrl);
            } else {
                initialView.setGroup(initialViewContent.bytesGroupOwnerAndUid);
            }
        });

        initialView.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(initialView.getContext(), initialView);
            if (viewModel.getAbsolutePhotoUrl() != null) {
                popup.inflate(R.menu.popup_details_photo_with_clear);
            } else {
                popup.inflate(R.menu.popup_details_photo);
            }
            popup.setOnMenuItemClickListener(item -> {
                int itemId = item.getItemId();
                if (itemId == R.id.popup_action_remove_image) {
                    viewModel.setAbsolutePhotoUrl(null);
                } else if (itemId == R.id.popup_action_choose_image) {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT)
                            .setType("image/*")
                            .addCategory(Intent.CATEGORY_OPENABLE);
                    App.startActivityForResult(this, intent, REQUEST_CODE_CHOOSE_IMAGE);
                } else if (itemId == R.id.popup_action_take_picture) {
                    try {
                        if (v.getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                            if (ContextCompat.checkSelfPermission(requireActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CODE_PERMISSION_CAMERA);
                            } else {
                                takePicture();
                            }
                        } else {
                            App.toast(R.string.toast_message_device_has_no_camera, Toast.LENGTH_SHORT);
                        }
                    } catch (IllegalStateException e) {
                        e.printStackTrace();
                    }
                }
                return true;
            });
            popup.show();
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE_PERMISSION_CAMERA: {
                try {
                    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        takePicture();
                    } else {
                        App.toast(R.string.toast_message_camera_permission_denied, Toast.LENGTH_SHORT);
                    }
                } catch (IllegalStateException e) {
                    e.printStackTrace();
                }
                break;
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_CHOOSE_IMAGE: {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startActivityForResult(new Intent(null, data.getData(), App.getContext(), SelectDetailsPhotoActivity.class), REQUEST_CODE_SELECT_ZONE);
                }
                break;
            }
            case REQUEST_CODE_TAKE_PICTURE: {
                if (resultCode == Activity.RESULT_OK && viewModel.getTakePictureUri() != null) {
                    startActivityForResult(new Intent(null, viewModel.getTakePictureUri(), App.getContext(), SelectDetailsPhotoActivity.class), REQUEST_CODE_SELECT_ZONE);
                }
                break;
            }
            case REQUEST_CODE_SELECT_ZONE: {
                if (resultCode == Activity.RESULT_OK) {
                    if (data != null) {
                        String absolutePhotoUrl = data.getStringExtra(SelectDetailsPhotoActivity.CROPPED_JPEG_RETURN_INTENT_EXTRA);
                        if (absolutePhotoUrl != null) {
                            viewModel.setAbsolutePhotoUrl(absolutePhotoUrl);
                        }
                    }
                }
                break;
            }
        }
    }

    private void takePicture() throws IllegalStateException {
        if (viewModel == null) {
            return;
        }
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        viewModel.setTakePictureUri(null);

        if (takePictureIntent.resolveActivity(requireActivity().getPackageManager()) != null) {
            File photoDir = new File(requireActivity().getCacheDir(), App.CAMERA_PICTURE_FOLDER);
            File photoFile = new File(photoDir, new SimpleDateFormat(App.TIMESTAMP_FILE_NAME_FORMAT, Locale.ENGLISH).format(new Date()) + ".jpg");
            try {
                //noinspection ResultOfMethodCallIgnored
                photoDir.mkdirs();
                if (!photoFile.createNewFile()) {
                    return;
                }
                Uri photoUri = FileProvider.getUriForFile(requireActivity(),
                        BuildConfig.APPLICATION_ID + ".PICTURE_FILES_PROVIDER",
                        photoFile);
                viewModel.setTakePictureUri(photoUri);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                App.startActivityForResult(this, takePictureIntent, REQUEST_CODE_TAKE_PICTURE);
            } catch (IOException e) {
                Logger.w("Error creating photo capture file " + photoFile.toString());
            }
        }
    }

    public void setUseDialogBackground(boolean useDialogBackground) {
        this.useDialogBackground = useDialogBackground;
    }
}