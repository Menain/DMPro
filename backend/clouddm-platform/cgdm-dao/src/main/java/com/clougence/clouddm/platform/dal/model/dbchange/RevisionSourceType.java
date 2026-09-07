/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clougence.clouddm.platform.dal.model.dbchange;

/**
 * Source type of a frozen revision.
 * PRE_TICKET: path A — frozen from a successful PRE governance ticket.
 * DIRECT_PROD_DML: path B — frozen from a direct-production DML ticket.
 */
public enum RevisionSourceType {
    PRE_TICKET,
    DIRECT_PROD_DML
}
