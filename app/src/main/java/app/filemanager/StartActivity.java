package app.filemanager;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStatVfs;
import android.text.InputType;
import android.util.Log;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.IdRes;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.RecyclerView;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;

public class StartActivity extends AppCompatActivity {

    AppCompatActivity activity = this;
    RecyclerView listView;
    Uri tempDirectory = null;
    util.ui.ListAdapter<Item,ViewHolder> listAdapter = null;
    ArrayList<Item> list;
    Button addStorageButton;
    DocumentFile mainStorage;
    int mainStorageIndex = -1;
    String view;
    @LayoutRes int listLayout = R.layout.listitemlayout;
    @LayoutRes int listGridLayout = R.layout.griditemlayout;
    util.database.DatabaseSettingsTable settings;
    boolean settingsLoaded = false;
    util.database.DatabaseTable storageMedia;
    util.database.DatabaseTable bookmarks;
    Menu mainMenu = null;
    ActivityResultLauncher<Intent> activityResultLauncher;
    final int NUM_COLUMNS = 4;

    protected static class Item {
        public Uri uri;
        public String name;
        public boolean isStorageMedia;
    }

    protected class ViewHolder extends util.ui.ListAdapter.ItemViewHolder<Item,ViewHolder> {
        TextView title;
        ImageView iconOrThumbnail;
        Button button;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.title);
            iconOrThumbnail = itemView.findViewById(R.id.image);
            button = itemView.findViewById(R.id.button);
            activateClickListener(button);
        }

        @Override
        protected void onItemClick(int index, @IdRes int viewId) {
            if (viewId==R.id.button) {
                if (mainStorageIndex!=-1 && index==mainStorageIndex)
                    openPopupMenu(R.menu.contextmenu_startactivity_main_storage,button);
                else
                    openPopupMenu(R.menu.contextmenu_startactivity_pinned_storage,button);
            }
            else {
                tempDirectory = getItem().uri;
                if (tempDirectory!=null && util.file.isUriPermissionGranted(activity,tempDirectory)) {
                        Intent openIntent = new Intent(activity,MainActivity.class);
                        openIntent.setData(tempDirectory);
                        tempDirectory = null;
                        activity.startActivity(openIntent);
                }
            }
        }

        @Override
        protected boolean onItemLongClick(int index, @IdRes int viewId) {
            return false;
        }

        @Override
        protected boolean onPopupMenuItemClick(int index, MenuItem menuItem, int menuItemId) {
            switch (menuItemId) {
                case R.id.rename:
                    new util.ui.TextInputDialog(activity, getString(R.string.rename_dialog_title), InputType.TYPE_CLASS_TEXT, getString(R.string.confirm_button_text), getString(R.string.cancel_button_text)) {
                        @Override
                        protected void onProceed(String inputText) {
                            if (getItem().isStorageMedia)
                                storageMedia.setEntry(index,"name",inputText);
                            else
                                bookmarks.setEntry(index,"name",inputText);
                            load();
                        }

                        @Override
                        protected void onDismiss(String inputText) {

                        }
                    }.open();
                    break;
                case R.id.delete:
                    delete(getItem());
                    break;
                case R.id.details:
                    showDetails(activity,getItem());
                    break;
            }
            return false;
        }

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_start);
        listView = activity.findViewById(R.id.list);
        addStorageButton = activity.findViewById(R.id.add_storage);
        settings = new util.database.DatabaseSettingsTable(activity,getString(R.string.settings_db_name),getString(R.string.settings_db_name),new util.database.DatabaseTable.Type[]{util.database.DatabaseTable.Type.String,util.database.DatabaseTable.Type.String,util.database.DatabaseTable.Type.String,util.database.DatabaseTable.Type.String},new String[]{"view","sortby","ascdesc","hidden"});
        view = settings.getStringEntry("view");
        if (view==null) {
            settings.setEntry("view","list");
            view = "list";
        }
        settingsLoaded = true;

        activityResultLauncher = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), new ActivityResultCallback<ActivityResult>(){
            @Override
            public void onActivityResult(ActivityResult result) {


                switch (result.getResultCode()) {
                    case Activity.RESULT_OK:
                        Intent intent = result.getData();
                        if (intent==null)
                            return;
                        Uri uri = intent.getData();
                        if (uri!=null) {
                            if (!uri.toString().equals(util.file.storageMediaUri(uri).toString())) {
                                addStorage();
                                break;
                            }
                            activity.getContentResolver().takePersistableUriPermission(uri,result.getData().getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                            Intent openIntent = new Intent(activity,MainActivity.class);
                            openIntent.setData(uri);
                            tempDirectory = null;
                            if (!util.file.isUriPermissionGranted(activity,uri) || !util.file.isWritable(activity,uri)) {
                                Toast.makeText(activity, getString(R.string.storage_media_add_error_message), Toast.LENGTH_LONG).show();
                            }
                            else {
                                if (!storageMedia.contains("path",uri.toString())) {
                                    HashMap<String, String> bookmark = new HashMap<>();
                                    String name = util.file.name(util.file.fileName(uri));
                                    if (name.equals("")) {
                                        int index = storageMedia.rows();
                                        if (mainStorageIndex!=-1)
                                            name = getString(R.string.default_storage_media_name)+" "+(index+2);
                                        else if (index==0)
                                            name = getString(R.string.default_storage_media_name);
                                        else
                                            name = getString(R.string.default_storage_media_name)+" "+(index+1);
                                    }
                                    bookmark.put("name", '"' + name + '"');
                                    bookmark.put("path", '"' + uri.toString() + '"');
                                    storageMedia.addRow(bookmark);
                                }
                                else {
                                    Toast.makeText(activity,getString(R.string.storage_media_exists_message),Toast.LENGTH_LONG).show();
                                }
                                load();
                                activity.startActivity(openIntent);
                            }
                        }
                        else {
                            Toast.makeText(activity,getString(R.string.storage_media_add_error_message),Toast.LENGTH_LONG).show();
                        }
                        break;
                    case Activity.RESULT_CANCELED:
                }
            }
        });

        addStorageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addStorage();
            }
        });

        listAdapter = new util.ui.ListAdapter<Item,ViewHolder>(activity,listView,listLayout,R.menu.actionmenu_startactivity,4) {
            @Override
            protected ViewHolder createViewHolder(View itemView, int layout) {
                return new ViewHolder(itemView);
            }

            @Override
            protected Bundle prepareItemViewUpdate(ViewHolder viewHolder, int index) {
                return null;
            }

            @Override
            protected void applyItemViewUpdate(Bundle update, ViewHolder viewHolder, int index) {
                Item item = getItemAtIndex(index);
                viewHolder.title.setText(item.name);
                if (item.isStorageMedia)
                    viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_device_drive, null));
                else if (item.name.equalsIgnoreCase("images")||item.name.equalsIgnoreCase("pictures"))
                    viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_folder_picture, null));
                else if (item.name.equalsIgnoreCase("music"))
                    viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_folder_music, null));
                else if (item.name.equalsIgnoreCase("video")||item.name.equalsIgnoreCase("videos"))
                    viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_folder_video, null));
                else if (item.name.equalsIgnoreCase("documents"))
                    viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_folder_document, null));
                else
                    viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_folder, null));
            }

            @Override
            protected void onActivateSelectionMode() {

            }

            @Override
            protected void onDeactivateSelectionMode() {

            }

            @Override
            public void onPrepareActionBar(ActionMode actionMode, Menu menu) {

            }

            @Override
            protected boolean onActionMenuItemClicked(ActionMode menu, MenuItem itemView, int itemId) {

                return false;
            }

        };

        if (view.equals("list")) {
            listAdapter.setLayoutToList(listLayout);
        }
        else {
            listAdapter.setLayoutToGrid(listGridLayout,NUM_COLUMNS);
        }

        if (Build.VERSION.SDK_INT<29) {
            DocumentFile storageRoot = util.file.fromUri(activity, Uri.parse("file:///storage"));
            for (DocumentFile storage : storageRoot.listFiles()) {
                if (util.file.isWritable(activity, storage)) {
                    mainStorage = storage;
                }
            }
        }
        load();

    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (settings.getStringEntry("view").equals("list")) {
            listAdapter.setLayoutToList(listLayout);
        }
        else {
            listAdapter.setLayoutToGrid(listGridLayout,NUM_COLUMNS);
        }
        load();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (!settingsLoaded) return false;
        getMenuInflater().inflate(R.menu.mainmenu_startactivity,menu);
        mainMenu = menu;
        MenuItem viewItem = menu.findItem(R.id.view);
        if (view.equals("list")) {
            viewItem.setTitle(getString(R.string.grid_view_title));
            viewItem.setIcon(R.drawable.view_grid_outline);
        }
        else {
            viewItem.setTitle(getString(R.string.list_view_title));
            viewItem.setIcon(R.drawable.view_list_outline);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (listAdapter!=null) {
            switch (item.getItemId()) {
                case R.id.view:
                    switch (view) {
                        case "grid":
                            listAdapter.setLayoutToList(listLayout);
                            settings.setEntry("view","list");
                            view = "list";
                            item.setTitle(getString(R.string.grid_view_title));
                            item.setIcon(R.drawable.view_grid_outline);
                            break;
                        case "list":
                            listAdapter.setLayoutToGrid(listGridLayout,NUM_COLUMNS);
                            settings.setEntry("view","grid");
                            view = "grid";
                            item.setTitle(getString(R.string.list_view_title));
                            item.setIcon(R.drawable.view_list_outline);
                            break;
                    }
                    break;
                case R.id.add_storage:
                    addStorage();
                    break;
                case R.id.about:
                    new util.ui.MessageDialog(activity,getString(R.string.about_dialog_title),getString(R.string.about_text),getString(R.string.close_button_text)) {
                        protected void onDismiss() {}
                    }.open();
                    break;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActionModeStarted(ActionMode mode) {
        super.onActionModeStarted(mode);
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
        super.onActionModeFinished(mode);
    }

    protected void load() {
        storageMedia = new util.database.DatabaseTable(activity,getString(R.string.pinned_db_name),getString(R.string.storage_media_table_name),new util.database.DatabaseTable.Type[]{util.database.DatabaseTable.Type.String,util.database.DatabaseTable.Type.String},new String[]{"name","path"});
        bookmarks = new util.database.DatabaseTable(activity,getString(R.string.pinned_db_name),getString(R.string.bookmarks_table_name),new util.database.DatabaseTable.Type[]{util.database.DatabaseTable.Type.String,util.database.DatabaseTable.Type.String},new String[]{"name","path"});
        list = new ArrayList<>();
        for (int index=0; index<bookmarks.rows(); index++) {
            Item item = new Item();
            item.name = bookmarks.getStringEntry(index,"name");
            item.uri = Uri.parse(bookmarks.getStringEntry(index,"path"));
            item.isStorageMedia = false;
            list.add(item);
        }
        if (Build.VERSION.SDK_INT<29) {
            Item mainStorageItem = new Item();
            mainStorageItem.uri = mainStorage.getUri();
            mainStorageItem.name = "Main Storage";
            mainStorageItem.isStorageMedia = true;
            list.add(mainStorageItem);
            mainStorageIndex = list.size() - 1;
        }
        for (int index=0; index<storageMedia.rows(); index++) {
            Item item = new Item();
            item.name = storageMedia.getStringEntry(index,"name");
            item.uri = Uri.parse(storageMedia.getStringEntry(index,"path"));
            item.isStorageMedia = true;
            list.add(item);
        }
        if (list.isEmpty()) {
            listView.setVisibility(View.GONE);
            addStorageButton.setVisibility(View.VISIBLE);
        }
        else {
            addStorageButton.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            listAdapter.setData(list);
        }
    }

    protected void addStorage() {
        new util.ui.OptionDialog(activity,getString(R.string.add_storage_media_dialog_title),getString(R.string.add_storage_media_message),getString(R.string.confirm_button_text),getString(R.string.cancel_button_text)) {

            @Override
            protected void button1Action() {
                Intent storageAccessIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                storageAccessIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        |Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                        |Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                activityResultLauncher.launch(storageAccessIntent);
            }

            @Override
            protected void button2Action() {

            }

            @Override
            protected void button3Action() {

            }

            @Override
            protected void finalAction() {

            }
        }.open();
    }

    protected void delete(Item item) {
        new util.ui.OptionDialog(activity,getString(R.string.delete_confirmation_title),getString(R.string.delete_confirmation_message),getString(R.string.confirm_button_text),getString(R.string.cancel_button_text)) {

            @Override
            protected void button1Action() {
                if (item.isStorageMedia) {
                    storageMedia.removeRow("path", '"' + item.uri.toString() + '"');
                }
                else {
                    bookmarks.removeRow("path", '"' + item.uri.toString() + '"');
                }
                load();
            }

            @Override
            protected void button2Action() {

            }

            @Override
            protected void button3Action() {

            }

            @Override
            protected void finalAction() {

            }
        }.open();

    }

    private void showDetails(AppCompatActivity activity, Item item) {
        DocumentFile f = util.file.fromUri(activity,item.uri);
        StringBuilder details = new StringBuilder();
        details.append(getString(R.string.details_name));
        details.append(item.name);
        if (item.isStorageMedia) {
            try {
                StructStatVfs volumeInfo = Os.fstatvfs(activity.getContentResolver().openFileDescriptor(f.getUri(),"r").getFileDescriptor());
                details.append("\n").append(getString(R.string.details_volumesize));
                details.append(util.file.formatSize(volumeInfo.f_blocks*volumeInfo.f_bsize,2));
                details.append("\n").append(getString(R.string.details_freespace));
                details.append(util.file.formatSize(volumeInfo.f_bavail*volumeInfo.f_bsize,2));
            }
            catch (FileNotFoundException|ErrnoException ignored) {details.append("Unknown size");}
            details.append("\n").append(getString(R.string.details_type)).append(getString(R.string.storage_media_type));
        }
        else {
            details.append("\n").append(getString(R.string.details_type)).append(util.file.typeAsString(f));
        }
        new util.ui.MessageDialog(activity,getString(R.string.details_dialog_title),details.toString(),getString(R.string.close_button_text)) {
            protected void onDismiss() {}
        }.open();
    }

}
