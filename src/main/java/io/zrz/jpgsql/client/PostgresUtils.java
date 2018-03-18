package io.zrz.jpgsql.client;

import java.util.ArrayList;
import java.util.List;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;

import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PostgresUtils {

  public static Function<QueryResult, Single<CommandStatus>> statusMapper() {
    return (res) -> {
      if (res instanceof CommandStatus) {
        return Single.just((CommandStatus) res);
      }
      else if (res instanceof ErrorResult) {
        return Single.error((ErrorResult) res);
      }
      return Single.error(new RuntimeException(res.toString()));
    };
  }

  public static Function<QueryResult, Maybe<CommandStatus>> commandStatusMapper() {
    return (res) -> {
      if (res instanceof CommandStatus) {
        return Maybe.just((CommandStatus) res);
      }
      return Maybe.error(new RuntimeException(res.toString()));
    };
  }

  public static Function<QueryResult, Flowable<PgResultRow>> rowMapper() {

    return (res) -> {

      if (res instanceof RowBuffer) {

        final RowBuffer rows = (RowBuffer) res;

        final List<PgResultRow> r = new ArrayList<>(rows.count());

        for (int i = 0; i < rows.count(); ++i) {
          r.add(new PgResultRow(rows, i));
        }

        return Flowable.fromIterable(r);

      }
      else if (res instanceof CommandStatus) {
        return Flowable.empty();
      }
      else if (res instanceof SecureProgress) {
        return Flowable.empty();
      }
      else if (res instanceof WarningResult) {
        log.info("info: {}", res);
        return Flowable.empty();
      }
      else if (res instanceof ErrorResult) {
        return Flowable.error((ErrorResult) res);
      }

      return Flowable.error(new RuntimeException(res.getClass().getSimpleName()));

    };

  }

  public static Function<PgResultRow, Publisher<? extends String>> flatArray(int column) {
    return row -> Flowable.fromIterable(row.textArray(column));
  }

  public static Consumer<? super QueryResult> log(Logger logger) {
    return res -> {

      if (res instanceof RowBuffer) {

        logger.debug("row: {}", res);

      }
      else if (res instanceof CommandStatus) {
        CommandStatus cmd = (CommandStatus) res;

        switch (cmd.getStatus()) {
          case "BEGIN":
          case "COMMIT":
            logger.debug("{}", cmd);
            break;
          default:
            if (cmd.getUpdateCount() == 0) {
              logger.debug("{}", cmd.getStatus());
            }
            else {
              logger.debug("{} ({} rows)", cmd.getStatus(), cmd.getUpdateCount());
            }
            break;
        }

      }
      else if (res instanceof SecureProgress) {
      }
      else if (res instanceof WarningResult) {
        WarningResult warning = ((WarningResult) res);
        switch (warning.getSeverity()) {
          case "NOTICE":
            logger.debug("{}", warning.getMessage());
            break;
          default:
            logger.warn("[{}] {}", warning.getSeverity(), warning.getMessage());
            break;
        }
      }
      else if (res instanceof ErrorResult) {
        logger.error("{}", res);
      }

    };
  }

}
