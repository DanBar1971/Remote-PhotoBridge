package pt.remotephotobridge;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** The receiver's hashes prevent re-transmission; sender cache prevents repeated local hashing. */
public final class TransferDb extends SQLiteOpenHelper {
    TransferDb(Context context) { super(context, "photobridge.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE received (sha256 TEXT PRIMARY KEY, saved_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE source_hash (media_id INTEGER PRIMARY KEY, size INTEGER NOT NULL, modified INTEGER NOT NULL, sha256 TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    synchronized boolean alreadyReceived(String digest) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM received WHERE sha256=? LIMIT 1", new String[]{digest})) {
            return c.moveToFirst();
        }
    }

    synchronized void markReceived(String digest) {
        ContentValues v = new ContentValues();
        v.put("sha256", digest);
        v.put("saved_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("received", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    synchronized String knownSourceHash(long id, long size, long modified) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT sha256 FROM source_hash WHERE media_id=? AND size=? AND modified=?",
                new String[]{Long.toString(id), Long.toString(size), Long.toString(modified)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    synchronized void saveSourceHash(long id, long size, long modified, String sha) {
        ContentValues v = new ContentValues();
        v.put("media_id", id);
        v.put("size", size);
        v.put("modified", modified);
        v.put("sha256", sha);
        getWritableDatabase().insertWithOnConflict("source_hash", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
