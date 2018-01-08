package io.zrz.jpgsql.client.opj;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.Collections;
import java.util.List;

import org.postgresql.core.Field;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.ResultHandlerBase;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLWarning;

import io.reactivex.FlowableEmitter;
import io.zrz.jpgsql.client.CommandStatus;
import io.zrz.jpgsql.client.ErrorResult;
import io.zrz.jpgsql.client.Query;
import io.zrz.jpgsql.client.QueryResult;
import io.zrz.jpgsql.client.SecureProgress;
import io.zrz.jpgsql.client.WarningResult;
import lombok.extern.slf4j.Slf4j;

/**
 * converts the responses from postgres to observable events.
 */

@Slf4j
public class PgObservableResultHandler extends ResultHandlerBase {

  private static int BATCH_SIZE = 5000;

  private final FlowableEmitter<QueryResult> emitter;
  private final Query query;

  private int statementId = 0;

  private int totalRows;

  PgObservableResultHandler(final Query query, final FlowableEmitter<QueryResult> emitter) {
    this.emitter = emitter;
    this.query = query;
  }

  @Override
  public void handleResultRows(final org.postgresql.core.Query fromQuery, final Field[] fields, final List<byte[][]> tuples, final ResultCursor cursor) {

    if (cursor != null) {
      log.error("don't support cursor queries - leak probable!");
    }

    // log.debug("received {} rows in statement {}", tuples.size(),
    // this.statementId);

    final PgResultMeta ifields = new PgResultMeta(fields);

    if (tuples.size() == 0) {

      // there were no results
      this.emitter.onNext(new PgResultRows(this.query, this.statementId, ifields, Collections.emptyList(), true));

    }
    else {

      int offset = 0;
      int remain = tuples.size();

      this.totalRows += remain;

      // we dispatch in batches - even though in the PgConnection implementation
      // we have everything at the end.

      while (remain > 0) {
        final int size = Math.min(remain, BATCH_SIZE);
        this.emitter.onNext(new PgResultRows(this.query, this.statementId, ifields, tuples.subList(offset, offset + size), (size == remain)));
        offset += size;
        remain -= size;
      }

    }

    this.statementId++;

  }

  @Override
  public void handleCommandStatus(final String status, final int updateCount, final long insertOID) {
    final CommandStatus msg = new CommandStatus(this.statementId, status, updateCount, insertOID);
    log.trace("[{}] {}", this.statementId, msg);
    this.statementId++;
    this.emitter.onNext(msg);
  }

  @Override
  public void handleCompletion() throws SQLException {
    // log.debug("finished Query ({} rows over {} statements). errors: {}",
    // this.totalRows, this.statementId, this.getException());
    if (this.getException() != null) {
      this.emitter.onError(this.getException());
    }
    else {
      this.emitter.onComplete();
    }
  }

  @Override
  public void secureProgress() {
    log.debug("secured progress");
    this.emitter.onNext(new SecureProgress(this.statementId));
  }

  /// ---

  /**
   * the only caller of handleWarning gets this:
   *
   * this is from any connection messages, which are, for example, debugging information not directly related to the
   * query (or explain output).
   *
   * we pass the warning on the same as others.
   *
   */

  @Override
  public void handleWarning(final SQLWarning warning) {
    final PSQLWarning warn = (PSQLWarning) warning;
    log.debug("SQL warning ({}): {}", warning.getClass(), warn.getServerErrorMessage());
    this.emitter.onNext(new WarningResult(this.statementId, warn.getServerErrorMessage()));
  }

  /**
   * this is called for each error, which there may be multiple.
   *
   * it only logs to trace, because we assume the client will handle.
   *
   */

  @Override
  public void handleError(final SQLException error) {
    final PSQLException err = (PSQLException) error;
    final ErrorResult res = new ErrorResult(
        query.statement(this.statementId),
        this.statementId,
        error.getMessage(),
        err.getSQLState(),
        err.getServerErrorMessage(),
        error.getCause());
    log.trace("SQL error received ({}: {}", error.getClass(), res);
    this.emitter.onError(res);

  }

}
