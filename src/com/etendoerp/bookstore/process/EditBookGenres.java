package com.etendoerp.bookstore.process;

import java.sql.Connection;

import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import org.hibernate.criterion.Restrictions;
import org.openbravo.client.application.process.BaseProcessActionHandler;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.base.provider.OBProvider;

import com.etendoerp.bookstore.data.Book;
import com.etendoerp.bookstore.data.BookGenre;
import com.etendoerp.bookstore.data.Genre;

public class EditBookGenres extends BaseProcessActionHandler {

    private static final Logger log = Logger.getLogger(EditBookGenres.class);

    private static final String OP_ADD = "ADD";
    private static final String OP_REMOVE = "REMOVE";
    private static final String OP_REPLACE = "REPLACE";

    @Override
    protected JSONObject doExecute(java.util.Map<String, Object> parameters, String content) {

        Connection conn = null;
        OBDal obdal = OBDal.getInstance();

        try {
            JSONObject request = new JSONObject(content);
            JSONObject params = request.optJSONObject("_params");

            if (params == null) {
                return buildError("Missing '_params' in request.");
            }

            String operation = params.optString("operation", "").trim().toUpperCase();
            if (operation.isEmpty()) {
                return buildRetry("Parameter 'operation' is required.");
            }

            if (!OP_ADD.equals(operation) && !OP_REMOVE.equals(operation) && !OP_REPLACE.equals(operation)) {
                return buildRetry("Unknown operation: " + operation);
            }

            JSONArray selectedBooks = request.optJSONArray("recordIds");
            if (selectedBooks == null || selectedBooks.length() == 0) {
                return buildRetry("No books selected. Select at least one book.");
            }

            String oldGenreId = emptyToNull(params.optString("oldGenre", null));
            String newGenreId = emptyToNull(params.optString("newGenre", null));

            if (OP_ADD.equals(operation) && newGenreId == null)
                return buildRetry("newGenre is required for ADD.");

            if (OP_REMOVE.equals(operation) && oldGenreId == null)
                return buildRetry("oldGenre is required for REMOVE.");

            if (OP_REPLACE.equals(operation) && (oldGenreId == null || newGenreId == null))
                return buildRetry("oldGenre and newGenre are required for REPLACE.");

            OBContext.setAdminMode(true);
            conn = obdal.getConnection();
            conn.setAutoCommit(false);

            int processed = 0;
            int created = 0;
            int removed = 0;

            for (int i = 0; i < selectedBooks.length(); i++) {

                String bookId = selectedBooks.getString(i);
                Book book = obdal.get(Book.class, bookId);

                if (book == null) {
                    throw new RuntimeException("Book not found: " + bookId);
                }

                processed++;

                switch (operation) {

                    case OP_ADD:
                        created += addGenre(book, newGenreId, obdal);
                        break;

                    case OP_REMOVE:
                        removed += removeGenre(book, oldGenreId, obdal);
                        break;

                    case OP_REPLACE:
                        if (existsLink(book, oldGenreId, obdal)) {
                            removed += removeGenre(book, oldGenreId, obdal);
                            created += addGenre(book, newGenreId, obdal);
                        }
                        break;
                }
            }

            obdal.flush();
            conn.commit();

            return buildSuccess(processed, created, removed);

        } catch (Exception e) {
            log.error("Process failed, rolling back.", e);
            try {
                if (conn != null) conn.rollback();
            } catch (Exception ignored) {}
            return safeBuildError("Process aborted. No changes saved.\nError: " + e.getMessage());

        } finally {
            try {
                if (conn != null) conn.setAutoCommit(true);
            } catch (Exception ignored) {}
            OBContext.restorePreviousMode();
        }
    }

    private int addGenre(Book book, String genreId, OBDal obdal) {
        Genre genre = obdal.get(Genre.class, genreId);
        if (genre == null)
            throw new RuntimeException("Genre not found: " + genreId);

        if (existsLink(book, genreId, obdal)) return 0;

        BookGenre bg = OBProvider.getInstance().get(BookGenre.class);
        bg.setBKSBook(book);
        bg.setBKSGenre(genre);
        obdal.save(bg);
        return 1;
    }

    private int removeGenre(Book book, String genreId, OBDal obdal) {
        Genre genre = obdal.get(Genre.class, genreId);
        if (genre == null)
            throw new RuntimeException("Genre not found: " + genreId);

        OBCriteria<BookGenre> c = obdal.createCriteria(BookGenre.class);
        c.add(Restrictions.eq(BookGenre.PROPERTY_BKSBOOK, book));
        c.add(Restrictions.eq(BookGenre.PROPERTY_BKSGENRE, genre));

        int removed = 0;
        for (BookGenre bg : c.list()) {
            obdal.remove(bg);
            removed++;
        }
        return removed;
    }

    private boolean existsLink(Book book, String genreId, OBDal obdal) {
        Genre genre = obdal.get(Genre.class, genreId);
        if (genre == null) return false;

        OBCriteria<BookGenre> c = obdal.createCriteria(BookGenre.class);
        c.add(Restrictions.eq(BookGenre.PROPERTY_BKSBOOK, book));
        c.add(Restrictions.eq(BookGenre.PROPERTY_BKSGENRE, genre));
        return c.count() > 0;
    }

    private String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private JSONObject buildSuccess(int processed, int created, int removed) throws JSONException {
        JSONObject root = new JSONObject();
        JSONArray actions = new JSONArray();

        JSONObject msg = new JSONObject();
        msg.put("msgType", "success");
        msg.put("msgTitle", "EditBookGenres - Result");
        msg.put("msgText",
                "Processed: " + processed +
                        ". Added: " + created +
                        ". Removed: " + removed + ".");

        JSONObject action = new JSONObject();
        action.put("showMsgInProcessView", msg);
        actions.put(action);

        root.put("responseActions", actions);
        return root;
    }

    private JSONObject buildRetry(String text) throws JSONException {
        JSONObject r = new JSONObject();
        r.put("retryExecution", true);

        JSONObject msg = new JSONObject();
        msg.put("severity", "error");
        msg.put("text", text);

        r.put("message", msg);
        return r;
    }

    private JSONObject buildError(String text) throws JSONException {
        JSONObject r = new JSONObject();
        JSONObject msg = new JSONObject();
        msg.put("severity", "error");
        msg.put("text", text);
        r.put("message", msg);
        return r;
    }

    private JSONObject safeBuildError(String text) {
        try {
            return buildError(text);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
