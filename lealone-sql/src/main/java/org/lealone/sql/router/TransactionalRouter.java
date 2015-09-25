/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.sql.router;

import org.lealone.common.message.DbException;
import org.lealone.db.CommandInterface;
import org.lealone.db.Session;
import org.lealone.db.result.ResultInterface;
import org.lealone.sql.Prepared;
import org.lealone.sql.ddl.DefineCommand;
import org.lealone.sql.dml.Delete;
import org.lealone.sql.dml.Insert;
import org.lealone.sql.dml.Merge;
import org.lealone.sql.dml.Select;
import org.lealone.sql.dml.TransactionCommand;
import org.lealone.sql.dml.Update;

public class TransactionalRouter implements Router {
    private final Router nestedRouter;

    public TransactionalRouter(Router nestedRouter) {
        this.nestedRouter = nestedRouter;
    }

    private void beginTransaction(Prepared p) {
        p.getSession().getTransaction(p);
    }

    @Override
    public int executeDefineCommand(DefineCommand defineCommand) {
        beginTransaction(defineCommand);
        return nestedRouter.executeDefineCommand(defineCommand);
    }

    @Override
    public ResultInterface executeSelect(Select select, int maxRows, boolean scrollable) {
        beginTransaction(select);
        return nestedRouter.executeSelect(select, maxRows, scrollable);
    }

    @Override
    public int executeInsert(Insert insert) {
        return execute(insert);
    }

    @Override
    public int executeMerge(Merge merge) {
        return execute(merge);
    }

    @Override
    public int executeDelete(Delete delete) {
        return execute(delete);
    }

    @Override
    public int executeUpdate(Update update) {
        return execute(update);
    }

    private int execute(Prepared p) {
        beginTransaction(p);

        boolean isTopTransaction = false;
        boolean isNestedTransaction = false;
        Session session = p.getSession();

        try {
            if (!p.isLocal() && p.isBatch()) {
                if (session.isAutoCommit()) {
                    session.setAutoCommit(false);
                    isTopTransaction = true;
                } else {
                    isNestedTransaction = true;
                    session.addSavepoint(TransactionCommand.INTERNAL_SAVEPOINT);
                }
            }
            int updateCount = 0;
            switch (p.getType()) {
            case CommandInterface.INSERT:
                updateCount = nestedRouter.executeInsert((Insert) p);
                break;
            case CommandInterface.UPDATE:
                updateCount = nestedRouter.executeUpdate((Update) p);
                break;
            case CommandInterface.DELETE:
                updateCount = nestedRouter.executeDelete((Delete) p);
                break;
            case CommandInterface.MERGE:
                updateCount = nestedRouter.executeMerge((Merge) p);
                break;
            }
            if (isTopTransaction)
                session.commit(false);
            return updateCount;
        } catch (Exception e) {
            if (isTopTransaction)
                session.rollback();

            // 嵌套事务出错时提前rollback
            if (isNestedTransaction)
                session.rollbackToSavepoint(TransactionCommand.INTERNAL_SAVEPOINT);

            throw DbException.convert(e);
        } finally {
            if (isTopTransaction)
                session.setAutoCommit(true);
        }
    }
}