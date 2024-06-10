/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution;

import com.facebook.presto.Session;
import com.facebook.presto.common.QualifiedObjectName;
import com.facebook.presto.common.util.ConfigUtil;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.MaterializedViewDefinition;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.security.AccessControl;
import com.facebook.presto.sql.analyzer.SemanticException;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.RenameColumn;
import com.facebook.presto.transaction.TransactionManager;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.common.constant.ConfigConstants.ENABLE_MIXED_CASE_SUPPORT;
import static com.facebook.presto.metadata.MetadataUtil.createQualifiedObjectName;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.COLUMN_ALREADY_EXISTS;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.MISSING_COLUMN;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.MISSING_TABLE;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.NOT_SUPPORTED;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.Locale.ENGLISH;

public class RenameColumnTask
        implements DDLDefinitionTask<RenameColumn>
{
    @Override
    public String getName()
    {
        return "RENAME COLUMN";
    }

    @Override
    public ListenableFuture<?> execute(RenameColumn statement, TransactionManager transactionManager, Metadata metadata, AccessControl accessControl, Session session, List<Expression> parameters, WarningCollector warningCollector)
    {
        QualifiedObjectName tableName = createQualifiedObjectName(session, statement, statement.getTable());
        Optional<TableHandle> tableHandleOptional = metadata.getMetadataResolver(session).getTableHandle(tableName);
        if (!tableHandleOptional.isPresent()) {
            if (!statement.isTableExists()) {
                throw new SemanticException(MISSING_TABLE, statement, "Table '%s' does not exist", tableName);
            }
            return immediateFuture(null);
        }

        Optional<MaterializedViewDefinition> optionalMaterializedView = metadata.getMetadataResolver(session).getMaterializedView(tableName);
        if (optionalMaterializedView.isPresent()) {
            if (!statement.isTableExists()) {
                throw new SemanticException(NOT_SUPPORTED, statement, "'%s' is a materialized view, and rename column is not supported", tableName);
            }
            return immediateFuture(null);
        }

        TableHandle tableHandle = tableHandleOptional.get();

        String source = statement.getSource().getValueLowerCase();
        String target = statement.getTarget().getValueLowerCase();
        if (!ConfigUtil.getConfig(ENABLE_MIXED_CASE_SUPPORT)) {
            source = source.toLowerCase(ENGLISH);
            target = target.toLowerCase(ENGLISH);
        }

        accessControl.checkCanRenameColumn(session.getRequiredTransactionId(), session.getIdentity(), session.getAccessControlContext(), tableName);

        Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, tableHandle);
        ColumnHandle columnHandle = columnHandles.get(source);
        if (columnHandle == null) {
            if (!statement.isColumnExists()) {
                throw new SemanticException(MISSING_COLUMN, statement, "Column '%s' does not exist", source);
            }
            return immediateFuture(null);
        }

        String columnName = target;
        if (columnHandles.keySet().stream().anyMatch(key -> key.toLowerCase(ENGLISH).equals(columnName.toLowerCase(ENGLISH)))) {
            throw new SemanticException(COLUMN_ALREADY_EXISTS, statement, "Column '%s' already exists", target);
        }

        if (metadata.getColumnMetadata(session, tableHandle, columnHandle).isHidden()) {
            throw new SemanticException(NOT_SUPPORTED, statement, "Cannot rename hidden column");
        }

        metadata.renameColumn(session, tableHandle, columnHandle, target);

        return immediateFuture(null);
    }
}
