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
package com.clougence.clouddm.sdk.model.ghost;

/**
 * Method keys dispatched by {@code Tool.invoke(methodKey, req)} for the GHOST tool, mirroring
 * {@code FakerMethod}. PAUSE/RESUME map to the gh-ost throttle flag file, STOP to the panic
 * flag file (gh-ost aborts immediately).
 */
public interface GhostMethod {

    String START  = "START";
    String PAUSE  = "PAUSE";
    String RESUME = "RESUME";
    String STOP   = "STOP";
}
