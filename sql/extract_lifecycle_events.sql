-- =============================================================================
-- M1: Google Cluster Trace v3 — Single-Pass Lifecycle Aggregation Query
-- =============================================================================
-- Target Dataset : google.com:google-cluster-data.clusterdata_2019_a
-- Output         : 1 consolidated row per task instance (collection_type = 0)
-- Cost Reduction : 3x–5x fewer rows vs raw event export (no alloc instances,
--                  GROUP BY collapses per-event rows into per-task rows)
-- Pre-computes   : queue_time_us, exec_time_us, total_residence_time_us
--
-- Usage (via bq_extract.sh):
--   bq query --use_legacy_sql=false \
--     --parameter=start_time_us:INT64:600000000 \
--     --parameter=end_time_us:INT64:1500000000 \
--     "$(cat sql/extract_lifecycle_events.sql)"
--
-- Time window reference (microseconds from trace epoch):
--   Dev 15-min  : start=600000000   end=1500000000   (900s window)
--   1-hour      : start=600000000   end=4200000000
--   12-hour     : start=600000000   end=43800000000
--   24-hour     : start=600000000   end=87000000000
-- =============================================================================

WITH task_events AS (
  SELECT
    collection_id,
    instance_index,
    alloc_collection_id,
    alloc_instance_index,
    priority,
    scheduling_class,
    resource_request.cpus    AS req_cpus,
    resource_request.memory  AS req_memory,
    type,
    time,
    machine_id
  FROM
    `google.com:google-cluster-data.clusterdata_2019_a.instance_events`
  WHERE
    collection_type = 0          -- Job tasks only (exclude alloc set instances)
    AND time >= @start_time_us
    AND time <  @end_time_us
),

aggregated_tasks AS (
  SELECT
    collection_id,
    instance_index,

    -- Arrival Time: first SUBMIT event (type = 0)
    MIN(CASE WHEN type = 0 THEN time END) AS submit_time_us,

    -- Execution Start: first SCHEDULE event (type = 3)
    MIN(CASE WHEN type = 3 THEN time END) AS schedule_time_us,

    -- Termination: earliest final event (EVICT=4, FAIL=5, FINISH=6, KILL=7, LOST=8)
    MIN(CASE WHEN type IN (4, 5, 6, 7, 8) THEN time END) AS finish_time_us,

    -- Final termination status code (highest priority terminal event wins)
    MAX(CASE WHEN type IN (4, 5, 6, 7, 8) THEN type END) AS final_event_type,

    -- Host machine where task was scheduled
    MAX(CASE WHEN type = 3 THEN machine_id END) AS scheduled_machine_id,

    -- Resource request: take maximum observed across all events for the instance
    MAX(req_cpus)          AS req_cpus,
    MAX(req_memory)        AS req_memory,
    MAX(priority)          AS priority,
    MAX(scheduling_class)  AS scheduling_class,
    MAX(alloc_collection_id)    AS alloc_collection_id,
    MAX(alloc_instance_index)   AS alloc_instance_index
  FROM
    task_events
  GROUP BY
    collection_id,
    instance_index
),

collection_meta AS (
  -- Fetch user hash and logical job template name for edge device mapping & RL features
  SELECT
    collection_id,
    user,
    collection_logical_name
  FROM
    `google.com:google-cluster-data.clusterdata_2019_a.collection_events`
  WHERE
    collection_type = 0   -- Job collections only
    AND type = 0          -- SUBMIT event carries the metadata
)

SELECT
  t.collection_id,
  t.instance_index,

  -- Raw lifecycle timestamps (microseconds)
  t.submit_time_us,
  t.schedule_time_us,
  t.finish_time_us,

  -- Pre-computed time deltas (microseconds)
  (t.schedule_time_us - t.submit_time_us)  AS queue_time_us,
  (t.finish_time_us   - t.schedule_time_us) AS exec_time_us,
  (t.finish_time_us   - t.submit_time_us)   AS total_residence_time_us,

  -- Termination outcome
  t.final_event_type,
  CASE
    WHEN t.final_event_type = 6 THEN 'FINISH'
    WHEN t.final_event_type = 5 THEN 'FAIL'
    WHEN t.final_event_type = 7 THEN 'KILL'
    WHEN t.final_event_type = 4 THEN 'EVICT'
    WHEN t.final_event_type = 8 THEN 'LOST'
    ELSE                              'UNKNOWN'
  END AS finish_status,

  -- Resource requests (normalized: CPU in NCUs [0,1], memory fraction [0,1])
  t.req_cpus,
  t.req_memory,

  -- Priority & scheduling class (key RL features)
  t.priority,
  t.scheduling_class,

  -- Infrastructure context
  t.scheduled_machine_id,
  t.alloc_collection_id,

  -- User/job identity for edge device mapping & RL workload categorization
  m.user,
  m.collection_logical_name

FROM
  aggregated_tasks AS t
LEFT JOIN
  collection_meta AS m
ON
  t.collection_id = m.collection_id

WHERE
  -- Only include tasks with a valid submission arrival time
  t.submit_time_us IS NOT NULL
