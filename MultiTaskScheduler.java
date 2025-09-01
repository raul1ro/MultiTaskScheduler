import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The MultiTaskScheduler executes, in parallel, a list of tasks.<br>
 * Every task run periodically at the specified frequency.<br<br>
 * The behavior of MultiTaskScheduler is like {@link ScheduledThreadPoolExecutor#scheduleAtFixedRate(Runnable, long, long, TimeUnit)}<br>
 * But if a task takes longer to execute than its scheduling frequency, subsequent executions will be skipped, instead of being queued.<br><br>
 * <b>The coordinator</b> is a platform thread(daemon=true).<br>
 * <b>The task executor</b> is {@link Executors#newThreadPerTaskExecutor} with factory as {@link Thread#ofVirtual}<br><br>
 */
public class MultiTaskScheduler implements AutoCloseable{

	/**
	 * Used for conversion ms -> ns
	 */
	private static final long NANOS_PER_MS = 1_000_000L;

	/**
	 * Task with details about it.
	 */
	private static final class Task {

		/**
		 * The name of the task.
		 */
		final String name;

		/**
		 * The frequency of execution.
		 */
		final long freqNS;

		/**
		 * The actual work of the task.
		 */
		final Runnable work;

		/**
		 * Flag to know if the task is running or not.
		 */
		final AtomicBoolean busy = new AtomicBoolean(false);

		/**
		 * The moment of next execution.
		 */
		volatile long nextNS;

		Task(String name, long startDelayMS, long freqMS, Runnable task, Consumer<Throwable> errorHandler) {

			//check for valid values
			if (startDelayMS < 0) throw new IllegalArgumentException("startDelayMS must be >= 0");
			if (freqMS <= 0) throw new IllegalArgumentException("freqMS must be > 0");

			this.name = name;
			this.nextNS = System.nanoTime() + (startDelayMS * NANOS_PER_MS);
			this.freqNS = freqMS * NANOS_PER_MS;

			//wrap the task into try-catch and also setting a name during execution.
			this.work = () -> {

				Thread t = Thread.currentThread();
				String initialName = t.getName();

				try{
					t.setName(name);
					task.run();
				}catch(Throwable e){
					errorHandler.accept(e);
				}finally {
					t.setName(initialName); //set back the initial name
				}

			};

		}

	}

	/**
	 * List of tasks.<br>
	 * Use `CopyOnWriteArrayList` for concurrent syncing between iteration and add.
	 */
	private final List<Task> tasks = new CopyOnWriteArrayList<>();

	/**
	 * Task executor with virtual threads.
	 */
	private final ExecutorService taskExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("TaskExecutor").factory());

	/**
	 * The thread which coordinates the tasks.
	 */
	private final Thread coordinator;

	/**
	 * The state of scheduler
	 */
	private final AtomicBoolean running = new AtomicBoolean(false);

	/**
	 * Instantiate the coordinator as platform thread (daemon - non-blocking jvm) .
	 * @param uncaughtExceptionHandler Handler with 2 inputs {thread, throwable}.
	 */
	public MultiTaskScheduler(BiConsumer<Thread, Throwable> uncaughtExceptionHandler) {

		this.coordinator = Thread.ofPlatform()
			.daemon(true)
			.uncaughtExceptionHandler(uncaughtExceptionHandler::accept)
			.unstarted(this::loop);

	}

	/**
	 * Add a task to the list.
	 * @param name The name of the task. Unique value.
	 * @param startDelayMs Initial delay before starting the execution.
	 * @param freqMS Frequency of execution.
	 * @param task The task for execution.
	 * @param errorhandler The error handler for task errors.
	 * @throws IllegalArgumentException Duplicate task name
	 */
	public void addTask(String name, long startDelayMs, long freqMS, Runnable task, Consumer<Throwable> errorhandler) throws IllegalArgumentException {

		if(tasks.stream().anyMatch(t -> t.name.equals(name))) {
			throw new IllegalArgumentException("Duplicate task name: " + name);
		}

		tasks.add(new Task(name, startDelayMs, freqMS, task, errorhandler));

		//unpark the coordinator
		//It can be the case when the coordinator is parked,
		// and the new task should be executed.
		// So, unpark to update the nearest next moment
		LockSupport.unpark(coordinator);

	}

	/**
	 * Remove task with the given name.
	 * @param name The name of the task to be removed.
	 */
	public void removeTask(String name){

		boolean removed = tasks.removeIf(t -> t.name.equals(name));

		//unpark the coordinator
		//to update the nearest next moment.
		if(removed){
			LockSupport.unpark(coordinator);
		}

	}

	/**
	 * Start the scheduler.
	 */
	public void start() {

		//start the coordinator
		// but only if it's not running already
		if(running.compareAndSet(false, true)){

			//start the coordinator
			coordinator.start();

		}

	}

	/**
	 * Close the scheduler.
	 */
	@Override
	public void close(){

		//set the flag
		running.set(false);

		//shutdown the executor
		taskExecutor.shutdown();

		//interrupt the coordinator
		coordinator.interrupt();

	}

	/**
	 * The loop method which coordinate the tasks.
	 */
	private void loop(){

		//run into "infinite" loop.
		while(running.get()){

			long now = System.nanoTime();
			long nearestNextMoment = Long.MAX_VALUE; //nearest moment from the next executions.

			//iterate every task
			for(Task task : tasks){

				//multiple usages and to keep nextNS atomically.
				long nextNS = task.nextNS;

				//if now moment is after the next moment of the task.
				//nanoTime() might overflow, so use it via difference.
				if(now - nextNS >= 0){

					//if the task is not busy
					// make it busy and execute
					// otherwise, skip the execution.
					if(task.busy.compareAndSet(false, true)){

						//execute, then free the busy flag
						taskExecutor.execute(() -> {
							try{
								task.work.run();
							}finally{
								task.busy.set(false);
							}
						});

					}

					//calculate the nextNS
					//the next moment will be calculated even if there was no execution
					long diff = now - nextNS; //difference from next execution to now
					long runs = 1 + (diff / task.freqNS); //how much runs fit in the diff and +1 to advance at least one run
					nextNS += (runs * task.freqNS); //transform the number runs to time and add it to nextNS

					//nextNS is volatile, the change should be atomically. So I can't read and assign at same time.
					task.nextNS = nextNS;

				}

				//save the nearest moment for next execution.
				nearestNextMoment = Math.min(nearestNextMoment, task.nextNS);

			}

			//if there is no task
			if(nearestNextMoment == Long.MAX_VALUE){
				LockSupport.park(); //park it until a task will be added; check `addTask`
				continue;
			}

			//calculate the sleeping time.
			long sleep = nearestNextMoment - System.nanoTime();
			if(sleep > 0){
				LockSupport.parkNanos(sleep); //park the current thread (coordinator)
			}

		}

	}

}
