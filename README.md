# MultiTaskScheduler
Executes, in parallel, a list of tasks.

Every task run periodically at the specified frequency.

The behavior of <code>MultiTaskScheduler</code> is similar with <code>ScheduledThreadPoolExecutor#scheduleAtFixedRate</code>.

But if a task takes longer to execute than its scheduling frequency, subsequent executions will be skipped, instead of being queued.

The <code>MultiTaskScheduler</code> is implementing <code>AutoCloseable</code>, which permits to use it with a try-catch with resources.

<b>The coordinator</b> is a platform thread(daemon=true) and is used strictly to start the tasks at the needed time.

<b>The task executor</b> is <code>Executors#newThreadPerTaskExecutor</code> with factory as <code>Thread#ofVirtual</code> which is used to <code>execute</code> the task.

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
manually: <code>scheduler.close();</code>

automatically: <code>try(MultiTaskScheduler scheduler = new MultiTaskScheduler((thread, ueh) -> {}))</code>
