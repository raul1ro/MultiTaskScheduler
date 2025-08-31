# MultiTaskScheduler
Executes, in parallel, a list of tasks, and every task runs periodically at the specified frequency.

It sets the thread name for each task, instead of using the default one (e.g. `pool-1-thread-3` or the fixed name from `ThreadFactory`). This might be useful for logging and monitoring the application.

It also offers a more straightforward way to set error handlers.

The `MultiTaskScheduler` is implementing `AutoCloseable`, which permits to use it with a try-catch with resources.

<b>The coordinator</b> is a platform thread(daemon=true) and is used strictly to start the tasks at the needed time.

<b>The task executor</b> is `Executors #newThreadPerTaskExecutor` with factory as `Thread #ofVirtual` which is used to `execute` the task.

<hr>

The behavior of `MultiTaskScheduler` is similar with `ScheduledThreadPoolExecutor#scheduleAtFixedRate`.

But if a task takes longer to execute than its scheduling frequency, subsequent executions will be skipped, instead of being queued. 

Example of `scheduleAtFixedRate` behavior:
- Frequency: 100ms
- Normal execution time: under 100ms
- Edge case: execution may take 650ms, which is longer than the frequency.

Expected schedule: 0, 100, 200, 300, ..., 600, 700, ...

The behavior when a run last 650ms:
- Moment 0 : task starts and runs for 1 second.
- Moment 650: the first task ends. The scheduled runs from 100, 200, ... 600 are overdue. So the scheduler executes them immediately back-to-back without pause. Like a burst of execution.

In this case, the `MultiTaskScheduler` will drop the executions from 100, 200, ... 600, and will wait until 700, when starts a new task.

<hr>

<h2>How to use it:</h2>

<h3>New instance</h3>
<code>MultiTaskScheduler scheduler = new MultiTaskScheduler((thread, ueh) -> {
    //handle the unhandled exception.
});
</code>

<h3>Add task</h3>
<code>scheduler.addTask(
    "NameOfTask", //should be unique
    5_000, //start with delay in ms
    1_000, //frequency of execution in ms
    () -> { /* the task code */ }
    (e) -> { /* handel the errors in task */ }
);
</code>

<h3>Remove task</h3>
<code>scheduler.removeTask("NameOfTask");</code>

<h3>Start the scheduler</h3>
<code>scheduler.start();</code>

<h3>Stop the scheduler</h3>

manually: `scheduler.close();`

automatically: `try(MultiTaskScheduler scheduler = new MultiTaskScheduler((thread, ueh) -> {}))`
