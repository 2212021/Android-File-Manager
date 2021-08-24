package app.filemanager;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.*;
import androidx.annotation.IdRes;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Stack;

public class MainActivity extends AppCompatActivity {

    AppCompatActivity activity = this;
    RecyclerView listView;
    DocumentFile rootDirectory;
    DocumentFile currentDirectory = null;
    util.ui.ListAdapter<DocumentFile,ViewHolder> listAdapter = null;
    Stack<DocumentFile> backStack = new Stack<>();
    ArrayList<DocumentFile> clipBoard = new ArrayList<>();
    DocumentFile clipBoardCurrentDirectory;
    boolean clipBoardCut;
    boolean undoActivated = false;
    boolean pasteActivated = false;
    String lastAction = null;
    ArrayList<DocumentFile> lastActionPrevState = null;
    DocumentFile lastActionPrevStateDir = null;
    ArrayList<DocumentFile> lastActionCurrentState = null;
    DocumentFile lastActionCurrentStateDir = null;
    String view, sortby, ascdesc, showHiddenFiles;
    @LayoutRes int listLayout = R.layout.listitemlayout;
    @LayoutRes int listGridLayout = R.layout.griditemlayout;
    Comparator<DocumentFile> sortingComparator = null;
    util.ui.ListAdapter.Filter<DocumentFile> filter = null;
    util.database.DatabaseSettingsTable settings;
    boolean settingsLoaded = false;
    util.database.DatabaseTable bookmarks;
    Menu mainMenu = null;
    final int NUM_COLUMNS = 4;

    protected class ViewHolder extends util.ui.ListAdapter.ItemViewHolder<DocumentFile,ViewHolder> {
        TextView title;
        TextView subtitle;
        ImageView iconOrThumbnail;
        Button button;
        CheckBox checkBox;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.title);
            if (view.equals("list"))
                subtitle = itemView.findViewById(R.id.subtitle);
            iconOrThumbnail = itemView.findViewById(R.id.image);
            button = itemView.findViewById(R.id.button);
            activateClickListener(button);
            checkBox = itemView.findViewById(R.id.checkbox);
            activateClickListener(checkBox);
        }

        @Override
        protected void onItemClick(int index, @IdRes int viewId) {
            if (viewId==R.id.button) {
                if (getItem().isDirectory())
                    openPopupMenu(R.menu.contextmenu_mainactivity_directory,button);
                else
                    openPopupMenu(R.menu.contextmenu_mainactivity_file,button);
            }
            else if (viewId==R.id.checkbox) {
                switchSelection();
            }
            else {
                if (!isInSelectionMode()) {
                    DocumentFile item = getItem();
                    if (item != null) {
                        if (!item.isDirectory()) {
                            Intent openIntent = new Intent(Intent.ACTION_VIEW);
                            openIntent.setDataAndType(item.getUri(),util.file.mime(util.file.extension(item)));
                            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            activity.startActivity(openIntent);
                        } else {
                            DocumentFile[] filesInside = item.listFiles();
                            setItems(filesInside,filter);
                            backStack.add(currentDirectory);
                            currentDirectory = item;
                        }
                    }
                } else {
                    switchSelection();
                }
            }
        }

        @Override
        protected boolean onItemLongClick(int index, @IdRes int viewId) {
            switchSelection();
            return true;
        }

        @Override
        protected boolean onPopupMenuItemClick(int index, MenuItem menuItem, int menuItemId) {
            ArrayList<DocumentFile> item = new ArrayList<>();
            item.add(getItem());
            switch (menuItemId) {
                case R.id.rename:
                    undoActivated = true;
                    rename(activity,listAdapter,item,null);
                    break;
                case R.id.copy:
                    clipBoard = item;
                    clipBoardCurrentDirectory = currentDirectory;
                    clipBoardCut = false;
                    pasteActivated = true;
                    invalidateOptionsMenu();
                    break;
                case R.id.cut:
                    clipBoard = item;
                    clipBoardCurrentDirectory = currentDirectory;
                    clipBoardCut = true;
                    pasteActivated = true;
                    invalidateOptionsMenu();
                    break;
                case R.id.delete:
                    undoActivated = true;
                    invalidateOptionsMenu();
                    delete(activity,listAdapter,item);
                    break;
                case R.id.details:
                    showDetails(activity,currentDirectory,item);
                    break;
                case R.id.pin:
                    Uri uri = getItem().getUri();
                    if (!bookmarks.contains("path",uri.toString())) {
                        HashMap<String, String> bookmark = new HashMap<>();
                        String name = util.file.name(util.file.fileName(uri));
                        if (name.equals("")) {
                            int bookmarkIndex = bookmarks.rows();
                            name = getString(R.string.default_storage_media_name);
                            if (bookmarkIndex!=0)
                                name = name+" "+bookmarkIndex;
                        }
                        bookmark.put("name", '"' + name + '"');
                        bookmark.put("path", '"' + uri.toString() + '"');
                        bookmarks.addRow(bookmark);
                    }
                    else {
                        Toast.makeText(activity,getString(R.string.pinned_item_exists_message),Toast.LENGTH_LONG).show();
                    }
                    break;
            }
            return false;
        }

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        listView = activity.findViewById(R.id.list);
        settings = new util.database.DatabaseSettingsTable(activity,getString(R.string.settings_db_name),getString(R.string.settings_db_name),new util.database.DatabaseTable.Type[]{util.database.DatabaseTable.Type.String,util.database.DatabaseTable.Type.String,util.database.DatabaseTable.Type.String,util.database.DatabaseTable.Type.String},new String[]{"view","sortby","ascdesc","hidden"});
        view = settings.getStringEntry("view");
        sortby = settings.getStringEntry("sortby");
        ascdesc = settings.getStringEntry("ascdesc");
        showHiddenFiles = settings.getStringEntry("hidden");
        if (view==null) {
            settings.setEntry("view","list");
            view = "list";
        }
        if (sortby==null) {
            settings.setEntry("sortby","name");
            sortby = "name";
        }
        if (ascdesc==null) {
            settings.setEntry("ascdesc","asc");
            ascdesc = "asc";
        }
        if (showHiddenFiles==null) {
            settings.setEntry("hidden", "hide");
            showHiddenFiles = "hide";
        }
        settingsLoaded = true;
        bookmarks = new util.database.DatabaseTable(activity,getString(R.string.pinned_db_name),getString(R.string.bookmarks_table_name),new util.database.DatabaseTable.Type[]{util.database.DatabaseTable.Type.String,util.database.DatabaseTable.Type.String},new String[]{"name","path"});

        listAdapter = new util.ui.ListAdapter<DocumentFile,ViewHolder>(activity,listView,listLayout,R.menu.actionmenu_mainactivity,4) {
            @Override
            protected ViewHolder createViewHolder(View itemView, int layout) {
                return new ViewHolder(itemView);
            }

            @Override
            protected Bundle prepareItemViewUpdate(ViewHolder viewHolder, int index) {
                DocumentFile item = getItemAtIndex(index);
                String type = util.file.typeAsString(item);
                Bitmap thumbnail = util.file.thumbnail(activity,item);
                Bundle update = new Bundle();
                update.putString("type",type);
                update.putParcelable("thumbnail",thumbnail);
                return update;
            }

            @Override
            protected void applyItemViewUpdate(Bundle update, ViewHolder viewHolder, int index) {
                DocumentFile f = getItemAtIndex(index);
                viewHolder.title.setText(util.file.fileName(f));
                String type = update.getString("type");
                if (view.equals("list"))
                    viewHolder.subtitle.setText(type);
                Bitmap thumbnail = update.getParcelable("thumbnail");
                if (thumbnail!=null) {
                    viewHolder.iconOrThumbnail.setImageBitmap(thumbnail);
                }
                else {
                    switch (util.file.extension(f)) {
                        case "pdf":
                            viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(),R.drawable.paomedia_small_n_flat_file_pdf,null));
                            break;
                        default:
                            String category = util.file.category(f);
                            String name = util.file.fileName(f);
                            if (category!=null) {
                                switch (category) {
                                    case "directory":
                                        if (name.equalsIgnoreCase("images")||name.equalsIgnoreCase("pictures"))
                                            viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_folder_picture, null));
                                        else if (name.equalsIgnoreCase("music"))
                                            viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_folder_music, null));
                                        else if (name.equalsIgnoreCase("video")||name.equalsIgnoreCase("videos"))
                                            viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_folder_video, null));
                                        else if (name.equalsIgnoreCase("documents"))
                                            viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_folder_document, null));
                                        else
                                            viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_folder, null));
                                        break;
                                    case "text":
                                        viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_file_text, null));
                                        break;
                                    case "image":
                                        viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_file_picture, null));
                                        break;
                                    case "audio":
                                        viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_file_sound, null));
                                        break;
                                    case "video":
                                        viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_file_video, null));
                                        break;
                                    default:
                                        viewHolder.iconOrThumbnail.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.paomedia_small_n_flat_file_empty, null));
                                }
                            }
                    }
                }
                if (isInSelectionMode()) {
                    viewHolder.button.setVisibility(View.GONE);
                    viewHolder.checkBox.setVisibility(View.VISIBLE);
                }
                else {
                    viewHolder.checkBox.setVisibility(View.GONE);
                    viewHolder.button.setVisibility(View.VISIBLE);
                }
                viewHolder.checkBox.setChecked(isSelected(index));
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
                switch (itemId) {
                    case R.id.rename:
                        rename(activity,listAdapter,getSelectedItems(),null);
                        break;
                    case R.id.copy:
                        clipBoard = getSelectedItems();
                        clipBoardCurrentDirectory = currentDirectory;
                        clipBoardCut = false;
                        break;
                    case R.id.cut:
                        clipBoard = getSelectedItems();
                        clipBoardCurrentDirectory = currentDirectory;
                        clipBoardCut = true;
                        break;

                    case R.id.delete:
                        delete(activity,listAdapter,getSelectedItems());
                        break;
                    case R.id.details:
                        showDetails(activity,currentDirectory,getSelectedItems());
                        break;
                    case R.id.selectall:
                        selectAll();
                        break;
                    case R.id.deselectall:
                        deselectAll();
                        break;
                }
                return false;
            }

        };

        Uri uri = activity.getIntent().getData();
        rootDirectory = util.file.fromUri(activity,uri);
        currentDirectory = rootDirectory;
        if (view.equals("list")) {
            listAdapter.setLayoutToList(listLayout);
        }
        else {
            listAdapter.setLayoutToGrid(listGridLayout,NUM_COLUMNS);
        }

        switch (sortby) {
            case "name":
                if (ascdesc.equals("asc")) {
                    sortingComparator = new util.file.LexicographicalComparator(false);
                }
                else {
                    sortingComparator = new util.file.LexicographicalComparator(true);
                }
                break;
            case "date":
                if (ascdesc.equals("asc")) {
                    sortingComparator = new util.file.ChronologicalComparator(false);
                }
                else {
                    sortingComparator = new util.file.ChronologicalComparator(true);
                }
                break;
            case "size":
                if (ascdesc.equals("asc")) {
                    sortingComparator = new util.file.SizeComparator(false);
                }
                else {
                    sortingComparator = new util.file.SizeComparator(true);
                }
                break;
        }

        filter = new util.ui.ListAdapter.Filter<DocumentFile>() {
            @Override
            public boolean condition(DocumentFile item) {
                if (util.file.fileName(item).startsWith(".")) {
                    return showHiddenFiles.equals("show");
                }
                return true;
            }
        };
        listAdapter.setData(currentDirectory.listFiles(),sortingComparator,filter);

    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (view.equals("list")) {
            listAdapter.setLayoutToList(listLayout);
        }
        else {
            listAdapter.setLayoutToGrid(listGridLayout,NUM_COLUMNS);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (!settingsLoaded) return false;
        getMenuInflater().inflate(R.menu.mainmenu_mainactivity,menu);
        mainMenu = menu;
        MenuItem viewItem = menu.findItem(R.id.view);
        MenuItem sortByNameItem = menu.findItem(R.id.byname);
        MenuItem sortByDateItem = menu.findItem(R.id.bydate);
        MenuItem sortBySizeItem = menu.findItem(R.id.bysize);
        MenuItem ascdescItem = menu.findItem(R.id.ascdesc);
        MenuItem showHiddenMenuItem = menu.findItem(R.id.showhidden);

        if (view.equals("list")) {
            viewItem.setTitle(getString(R.string.grid_view_title));
            viewItem.setIcon(R.drawable.view_grid_outline);
        }
        else {
            viewItem.setTitle(getString(R.string.list_view_title));
            viewItem.setIcon(R.drawable.view_list_outline);
        }

        switch (sortby) {
            case "name":
                sortByNameItem.setChecked(true);
                break;
            case "date":
                sortByDateItem.setChecked(true);
                break;
            case "size":
                sortBySizeItem.setChecked(true);
                break;
        }

        if (ascdesc.equals("asc")) {
            ascdescItem.setTitle(getString(R.string.sort_descending_title));
        }
        else {
            ascdescItem.setTitle(getString(R.string.sort_ascending_title));
        }

        if (showHiddenFiles.equals("hide")) {
            showHiddenMenuItem.setTitle(getString(R.string.hidden_files_show_title));
        }
        else {
            showHiddenMenuItem.setTitle(getString(R.string.hidden_files_hide_title));
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.undo).setVisible(undoActivated);
        menu.findItem(R.id.paste).setVisible(pasteActivated);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (listAdapter!=null) {
            switch (item.getItemId()) {
                case R.id.paste:
                    undoActivated = true;
                    pasteActivated = false;
                    invalidateOptionsMenu();
                    copy(activity,clipBoardCurrentDirectory,clipBoard,currentDirectory,clipBoardCut);
                    break;
                case R.id.undo:
                    undoActivated = false;
                    invalidateOptionsMenu();
                    switch (lastAction) {
                        case "rename":
                            rename(activity,listAdapter,lastActionCurrentState,lastActionPrevState);
                            break;
                        case "copy":
                            delete(activity,listAdapter,lastActionCurrentState);
                            break;
                        case "cut":
                            copy(activity,lastActionCurrentStateDir,lastActionCurrentState,lastActionPrevStateDir,true);
                            break;
                    }
                    break;
                case R.id.new_folder:
                    new util.ui.TextInputDialog(activity,getString(R.string.new_folder_dialog_title),InputType.TYPE_CLASS_TEXT,getString(R.string.confirm_button_text),getString(R.string.cancel_button_text)) {
                        @Override
                        protected void onProceed(String inputText) {
                            DocumentFile dir = util.file.fromUri(activity,currentDirectory.getUri());
                            DocumentFile folder;
                            if (dir.findFile(inputText)==null) {
                                if ((folder=dir.createDirectory(inputText)) != null) {
                                    listAdapter.addItem(folder);
                                }
                            }
                            else
                                Toast.makeText(activity,getString(R.string.new_folder_exists),Toast.LENGTH_LONG).show();
                        }
                        @Override
                        protected void onDismiss(String inputText) {}
                    }.open();
                    break;
                case R.id.new_text_file:
                    new util.ui.TextInputDialog(activity,getString(R.string.new_file_dialog_title),InputType.TYPE_CLASS_TEXT,getString(R.string.confirm_button_text),getString(R.string.cancel_button_text)) {
                        @Override
                        protected void onProceed(String inputText) {
                            DocumentFile dir = util.file.fromUri(activity,currentDirectory.getUri());
                            DocumentFile file;
                            if (dir.findFile(inputText+".txt")==null)
                                if ((file=dir.createFile("text/plain",inputText+".txt"))!=null) {
                                    listAdapter.addItem(file);
                                }
                                else {
                                    Toast.makeText(activity,getString(R.string.new_file_not_created),Toast.LENGTH_LONG).show();
                                }
                            else
                                Toast.makeText(activity,getString(R.string.new_file_exists),Toast.LENGTH_LONG).show();
                        }
                        @Override
                        protected void onDismiss(String inputText) {}
                    }.open();
                    break;
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
                case R.id.byname:
                    sortby = "name";
                    if (ascdesc.equals("asc"))
                        sortingComparator = new util.file.LexicographicalComparator(false);
                    else
                        sortingComparator = new util.file.LexicographicalComparator(true);
                    item.setChecked(true);
                    settings.setEntry("sortby",sortby);
                    listAdapter.setData(currentDirectory.listFiles(),sortingComparator);
                    break;
                case R.id.bydate:
                    sortby = "date";
                    if (ascdesc.equals("asc"))
                        sortingComparator = new util.file.ChronologicalComparator(false);
                    else
                        sortingComparator = new util.file.ChronologicalComparator(true);
                    item.setChecked(true);
                    settings.setEntry("sortby",sortby);
                    listAdapter.setData(currentDirectory.listFiles(),sortingComparator);
                    break;
                case R.id.bysize:
                    sortby = "size";
                    if (ascdesc.equals("asc"))
                        sortingComparator = new util.file.SizeComparator(false);
                    else
                        sortingComparator = new util.file.SizeComparator(true);
                    item.setChecked(true);
                    settings.setEntry("sortby",sortby);
                    listAdapter.setData(currentDirectory.listFiles(),sortingComparator);
                    break;
                case R.id.ascdesc:
                    switch (ascdesc) {
                        case "asc":
                            if (sortby.equals("name"))
                                sortingComparator = new util.file.LexicographicalComparator(true);
                            else
                                sortingComparator = new util.file.ChronologicalComparator(true);
                            ascdesc = "desc";
                            settings.setEntry("ascdesc","desc");
                            listAdapter.setData(currentDirectory.listFiles(),sortingComparator);
                            item.setTitle(getString(R.string.sort_ascending_title));
                            break;
                        case "desc":
                            if (sortby.equals("name"))
                                sortingComparator = new util.file.LexicographicalComparator(false);
                            else
                                sortingComparator = new util.file.ChronologicalComparator(false);
                            ascdesc = "asc";
                            settings.setEntry("ascdesc","asc");
                            listAdapter.setData(currentDirectory.listFiles(),sortingComparator);
                            item.setTitle(getString(R.string.sort_descending_title));
                            break;
                    }
                    break;
                case R.id.showhidden:
                    switch (showHiddenFiles) {
                        case "show":
                            filter = new util.ui.ListAdapter.Filter<DocumentFile>() {
                                @Override
                                public boolean condition(DocumentFile item) {
                                    return !util.file.fileName(item).startsWith(".");
                                }
                            };
                            listAdapter.setData(currentDirectory.listFiles());
                            showHiddenFiles = "hide";
                            settings.setEntry("hidden","hide");
                            item.setTitle(getString(R.string.hidden_files_show_title));
                            break;
                        case "hide":
                            filter = new util.ui.ListAdapter.Filter<DocumentFile>() {
                                @Override
                                public boolean condition(DocumentFile item) {
                                    return true;
                                }
                            };
                            listAdapter.setData(currentDirectory.listFiles());
                            showHiddenFiles = "show";
                            settings.setEntry("hidden","show");
                            item.setTitle(getString(R.string.hidden_files_hide_title));
                            break;
                    }
                    break;
                case R.id.selectall:
                    listAdapter.selectAll();
                    break;
                case R.id.deselectall:
                    listAdapter.deselectAll();
                    break;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (currentDirectory.getUri().equals(rootDirectory.getUri())) {
            super.onBackPressed();
        }
        else {
            currentDirectory = backStack.pop();
            listAdapter.setData(currentDirectory.listFiles());
        }
    }

    @Override
    public void onActionModeStarted(ActionMode mode) {
        super.onActionModeStarted(mode);
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
        super.onActionModeFinished(mode);
    }

    private void showDetails(AppCompatActivity activity,DocumentFile directory, ArrayList<DocumentFile> f) {
        StringBuilder details = new StringBuilder();
        if (f.size()==1) {
            details.append(getString(R.string.details_name));
            details.append(util.file.name(f.get(0)));
            details.append(getString(R.string.details_type)).append(util.file.typeAsString(f.get(0)));
        }
        util.file.FileSizeGetter sizeGetter;
        util.ui.MessageDialog messageDialog = new util.ui.MessageDialog(activity,getString(R.string.details_dialog_title),details.toString()) {
            protected void onDismiss() {}
        };
        sizeGetter = new util.file.FileSizeGetter(activity,directory,f) {
            @Override
            protected void onProgressUpdate(DocumentFile directory, ArrayList<DocumentFile> files, int progress) {}

            @Override
            protected void onFinish(DocumentFile directory, ArrayList<DocumentFile> files, long sizeBytes) {
                StringBuilder sizeStr = new StringBuilder();
                if (files.size()>1)
                    sizeStr.append("\n").append(getString(R.string.details_num_files)).append(files.size());
                sizeStr.append("\n").append(getString(R.string.details_size)).append(util.file.formatSize(sizeBytes,2));
                messageDialog.setMessage(details.toString()+sizeStr);
            }
        };

        messageDialog.open();
        sizeGetter.start();
    }

    private void rename(AppCompatActivity activity, util.ui.ListAdapter<DocumentFile,ViewHolder> listAdapter, ArrayList<DocumentFile> paths, ArrayList<DocumentFile> newPaths) {
        if (newPaths!=null) {
            new util.file.FileRenamer(activity, paths, newPaths) {
                @Override
                protected void onProgressUpdate(ArrayList<DocumentFile> renamedFiles, ArrayList<DocumentFile> renamedFilesNewNames, int progress) {

                }

                @Override
                protected void onFinish(ArrayList<DocumentFile> prevPaths, ArrayList<DocumentFile> newPaths, IOException exception) {
                    lastAction = "rename";
                    lastActionPrevState = prevPaths;
                    lastActionCurrentState = newPaths;
                    mainMenu.findItem(R.id.undo).setVisible(true);
                    //invalidateOptionsMenu();
                    listAdapter.setData(currentDirectory.listFiles());
                    listAdapter.deselectAll();
                    if (exception != null && exception.getMessage() != null && !exception.getMessage().equals("")) {
                        Toast.makeText(activity, exception.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            }.start();
        }
        else {
            new util.ui.TextInputDialog(activity, getString(R.string.rename_dialog_title), InputType.TYPE_CLASS_TEXT, getString(R.string.confirm_button_text), getString(R.string.cancel_button_text)) {
                @Override
                protected void onProceed(String inputText) {
                    new util.file.FileRenamer(activity, paths, inputText) {
                        @Override
                        protected void onProgressUpdate(ArrayList<DocumentFile> renamedFiles, ArrayList<DocumentFile> renamedFilesNewNames, int progress) {

                        }
                        @Override
                        protected void onFinish(ArrayList<DocumentFile> prevPaths, ArrayList<DocumentFile> newPaths, IOException exception) {
                            lastAction = "rename";
                            lastActionPrevState = prevPaths;
                            lastActionCurrentState = newPaths;
                            //invalidateOptionsMenu();
                            listAdapter.setData(currentDirectory.listFiles());
                            listAdapter.deselectAll();
                            if (exception != null && exception.getMessage() != null && !exception.getMessage().equals("")) {
                                Toast.makeText(activity, exception.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        }
                    }.start();
                }

                @Override
                protected void onDismiss(String inputText) {
                }
            }.open();
        }
    }

    private void copy(AppCompatActivity activity, DocumentFile sourceDirectory, ArrayList<DocumentFile> sourcePaths, DocumentFile destinationDirectory, boolean cut) {
        if (sourcePaths.size()==0) return;
        lastActionPrevState = sourcePaths;
        lastActionCurrentStateDir = sourceDirectory;
        util.ui.ProgressDialog progressDialog = new util.ui.ProgressDialog(activity,cut?getString(R.string.move_progress_title):getString(R.string.copy_progress_title)) {
            @Override
            protected void onCancel() {

            }
        };
        final util.file.FileCopier.DuplicateFiles[] duplicateFilesHandling = new util.file.FileCopier.DuplicateFiles[1];
        new util.file.DuplicateFileNamesFinder(activity,sourceDirectory,sourcePaths,destinationDirectory) {
            @Override
            protected void onFinish(ArrayList<DocumentFile> duplicateFiles) {
                if (duplicateFiles.size()==0) {
                    util.file.FileCopier fileCopier = new util.file.FileCopier(activity,sourceDirectory,sourcePaths,currentDirectory, util.file.FileCopier.DuplicateFiles.SKIP,cut) {
                        @Override
                        protected void onProgressUpdate(DocumentFile prevDirectory, ArrayList<DocumentFile> copiedFiles, DocumentFile newDirectory, ArrayList<DocumentFile> copiedFilesNewNames, int progress) {
                            progressDialog.setProgress(progress);
                        }
                        @Override
                        protected void onFinish(DocumentFile prevDirectory, ArrayList<DocumentFile> prevPaths, DocumentFile newDirectory, ArrayList<DocumentFile> newPaths, IOException exception) {
                            lastAction = move?"move":"copy";
                            lastActionCurrentState = newPaths;
                            lastActionCurrentStateDir = newDirectory;
                            listAdapter.setData(newDirectory.listFiles());
                            if (exception!=null) {
                                Toast.makeText(activity, exception.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        }
                    };
                    progressDialog.open();
                    fileCopier.start();
                }
                else {
                    new util.ui.OptionDialog(activity,getString(R.string.copy_existing_items_found_title),getString(R.string.copy_existing_items_found_message),getString(R.string.copy_skip_button_text),getString(R.string.copy_rename_button_text),getString(R.string.copy_overwrite_button_text)) {
                        @Override
                        protected void button1Action() {
                            duplicateFilesHandling[0] = util.file.FileCopier.DuplicateFiles.SKIP;
                        }

                        @Override
                        protected void button2Action() {
                            duplicateFilesHandling[0] = util.file.FileCopier.DuplicateFiles.RENAME;
                        }

                        @Override
                        protected void button3Action() {
                            duplicateFilesHandling[0] = util.file.FileCopier.DuplicateFiles.OVERWRITE;
                        }

                        @Override
                        protected void finalAction() {
                            util.file.FileCopier fileCopier = new util.file.FileCopier(activity,clipBoardCurrentDirectory,clipBoard,currentDirectory, duplicateFilesHandling[0],cut) {
                                @Override
                                protected void onProgressUpdate(DocumentFile prevDirectory, ArrayList<DocumentFile> copiedFiles, DocumentFile newDirectory, ArrayList<DocumentFile> copiedFilesNewNames, int progress) {
                                    progressDialog.setProgress(progress);
                                }
                                @Override
                                protected void onFinish(DocumentFile prevDirectory, ArrayList<DocumentFile> prevPaths, DocumentFile newDirectory, ArrayList<DocumentFile> newPaths, IOException exception) {
                                    lastAction = cut?"move":"copy";
                                    lastActionPrevState = prevPaths;
                                    lastActionPrevStateDir = prevDirectory;
                                    lastActionCurrentState = newPaths;
                                    lastActionCurrentStateDir = newDirectory;
                                    listAdapter.setData(newDirectory.listFiles());
                                    if (exception!=null) {
                                        Toast.makeText(activity, exception.getMessage(), Toast.LENGTH_LONG).show();
                                    }
                                }
                            };
                            progressDialog.open();
                            fileCopier.start();
                        }
                    }.open();
                }
            }
        }.start();
    }

    private void delete(AppCompatActivity activity, util.ui.ListAdapter<DocumentFile,ViewHolder> listAdapter, ArrayList<DocumentFile> paths) {
        util.ui.ProgressDialog progressDialog = new util.ui.ProgressDialog(activity,getString(R.string.delete_progress_title)) {
            @Override
            protected void onCancel() {

            }
        };
        util.file.FileDeleter fileDeleter = new util.file.FileDeleter(activity,currentDirectory,paths) {
            @Override
            protected void onProgressUpdate(DocumentFile directory, ArrayList<DocumentFile> deletedFiles, int progress) {
                progressDialog.setProgress(progress);
            }

            @Override
            protected void onFinish(DocumentFile directory, ArrayList<DocumentFile> deletedFiles, IOException exception) {
                lastAction = "delete";
                lastActionPrevState = null;
                lastActionCurrentState = null;
                mainMenu.findItem(R.id.undo).setVisible(true);
                invalidateOptionsMenu();
                listAdapter.setData(directory.listFiles());
            }
        };
        new util.ui.OptionDialog(activity,getString(R.string.delete_confirmation_title),getString(R.string.delete_confirmation_message),getString(R.string.confirm_button_text),getString(R.string.cancel_button_text)) {
            @Override
            protected void button1Action() {
                progressDialog.open();
                fileDeleter.start();
            }
            @Override
            protected void button2Action() {}
            @Override
            protected void button3Action() {}
            @Override
            protected void finalAction() {}
        }.open();
    }
}
