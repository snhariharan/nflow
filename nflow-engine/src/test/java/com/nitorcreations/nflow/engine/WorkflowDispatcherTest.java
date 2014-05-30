package com.nitorcreations.nflow.engine;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import edu.umd.cs.mtc.MultithreadedTestCase;
import edu.umd.cs.mtc.TestFramework;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.nitorcreations.nflow.engine.service.RepositoryService;

public class WorkflowDispatcherTest extends BaseNflowTest {
  private WorkflowDispatcher dispatcher;
  private ThreadPoolTaskExecutor pool;

  @Mock
  private RepositoryService repository;

  @Mock
  private WorkflowExecutorFactory executorFactory;

  @Mock
  private Environment env;

  @Before
  public void setup() {
    when(env.getProperty("dispatcher.sleep.ms", Long.class, 5000l)).thenReturn(0l);
    when(env.getProperty("dispatcher.executor.queue.wait_until_threshold", Integer.class, 0)).thenReturn(0);
    pool = dispatcherPoolExecutor();
    dispatcher = new WorkflowDispatcher(pool, repository, executorFactory, env);
  }

  @Test
  public void exceptionDuringDispatcherExecutionCausesRetry() throws Throwable {
    class ExceptionDuringDispatcherExecutionCausesRetry extends MultithreadedTestCase {
      public void threadDispatcher() {
        when(repository.pollNextWorkflowInstanceIds(anyInt()))
            .thenReturn(ids(1))
            .thenThrow(new RuntimeException("Expected: exception during dispatcher execution"))
            .thenAnswer(waitForTickAndAnswer(2, ids(2), this));
        when(executorFactory.createExecutor(1)).thenReturn(fakeWorkflowExecutor(1, noOpRunnable()));
        when(executorFactory.createExecutor(2)).thenReturn(fakeWorkflowExecutor(2, noOpRunnable()));

        dispatcher.run();
      }

      public void threadShutdown() {
        waitForTick(1);
        dispatcher.shutdown();
      }

      @Override
      public void finish() {
        verify(repository, times(3)).pollNextWorkflowInstanceIds(anyInt());
        InOrder inOrder = inOrder(executorFactory);
        inOrder.verify(executorFactory).createExecutor(1);
        inOrder.verify(executorFactory).createExecutor(2);
      }
    }
    TestFramework.runOnce(new ExceptionDuringDispatcherExecutionCausesRetry());
  }

  @Test
  public void errorDuringDispatcherExecutionStopsDispatcher() throws Throwable {
    class ErrorDuringDispatcherExecutionStopsDispatcher extends MultithreadedTestCase {
      public void threadDispatcher() {
        when(repository.pollNextWorkflowInstanceIds(anyInt()))
            .thenThrow(new AssertionError())
            .thenReturn(ids(1));

        try {
          dispatcher.run();
          Assert.fail("Error should stop the dispatcher");
        } catch (AssertionError expected) {
          assertPoolIsShutdown(true);
        }
      }

      @Override
      public void finish() {
        verify(repository).pollNextWorkflowInstanceIds(anyInt());
        verify(executorFactory, never()).createExecutor(anyInt());
      }
    }
    TestFramework.runOnce(new ErrorDuringDispatcherExecutionStopsDispatcher());
  }

  @Test
  public void emptyPollResultCausesNoTasksToBeScheduled() throws Throwable {
    class EmptyPollResultCausesNoTasksToBeScheduled extends MultithreadedTestCase {
      public void threadDispatcher() {
        when(repository.pollNextWorkflowInstanceIds(anyInt()))
            .thenReturn(ids(), ids())
            .thenAnswer(waitForTickAndAnswer(2, ids(), this));
        dispatcher.run();
      }

      public void threadShutdown() {
        waitForTick(1);
        dispatcher.shutdown();
      }

      @Override
      public void finish() {
        verify(repository, times(3)).pollNextWorkflowInstanceIds(anyInt());
        verify(executorFactory, never()).createExecutor(anyInt());
      }
    }
    TestFramework.runOnce(new EmptyPollResultCausesNoTasksToBeScheduled());
  }

  @Test
  public void shutdownBlocksUntilPoolShutdown() throws Throwable {
    class ShutdownBlocksUntilPoolShutdown extends MultithreadedTestCase {
      public void threadDispatcher() {
        when(repository.pollNextWorkflowInstanceIds(anyInt()))
            .thenAnswer(waitForTickAndAnswer(2, ids(1), this));
        when(executorFactory.createExecutor(anyInt()))
            .thenReturn(fakeWorkflowExecutor(1, waitForTickRunnable(3, this)));

        dispatcher.run();
      }

      public void threadShutdown() {
        waitForTick(1);
        dispatcher.shutdown();
        assertPoolIsShutdown(true);
      }
    }
    TestFramework.runOnce(new ShutdownBlocksUntilPoolShutdown());
  }

  @Test
  public void shutdownCanBeInterrupted() throws Throwable {
    class ShutdownCanBeInterrupted extends MultithreadedTestCase {
      public void threadDispatcher() {
        when(repository.pollNextWorkflowInstanceIds(anyInt())).thenAnswer(new Answer<Object>() {
          @Override
          public Object answer(InvocationOnMock invocation) throws Throwable {
            waitForTick(2);
            getThreadByName("threadShutdown").interrupt();
            return ids(1);
          }
        });
        when(executorFactory.createExecutor(anyInt()))
            .thenReturn(fakeWorkflowExecutor(1, waitForTickRunnable(3, this)));

        dispatcher.run();
      }

      public void threadShutdown() {
        waitForTick(1);
        dispatcher.shutdown();
        assertPoolIsShutdown(false);
      }
    }
    TestFramework.runOnce(new ShutdownCanBeInterrupted());
  }

  @Test
  public void exceptionOnPoolShutdownIsNotPropagated() throws Throwable {
    class ExceptionOnPoolShutdownIsNotPropagated extends MultithreadedTestCase {
      private ThreadPoolTaskExecutor poolSpy;

      @Override
      public void initialize() {
        poolSpy = Mockito.spy(pool);
        dispatcher = new WorkflowDispatcher(poolSpy, repository, executorFactory, env);
      }

      public void threadDispatcher() {
        when(repository.pollNextWorkflowInstanceIds(anyInt())).thenAnswer(waitForTickAndAnswer(2, ids(), this));
        doThrow(new RuntimeException("Expected: exception on pool shutdown")).when(poolSpy).shutdown();

        dispatcher.run();
      }

      public void threadShutdown() {
        waitForTick(1);
        dispatcher.shutdown();
      }

      @Override
      public void finish() {
        verify(poolSpy).shutdown();
      }
    }
    TestFramework.runOnce(new ExceptionOnPoolShutdownIsNotPropagated());
  }

  @Test
  public void shutdownCanBeCalledMultipleTimes() throws Throwable {
    class ShutdownCanBeCalledMultipleTimes extends MultithreadedTestCase {
      public void threadDispatcher() throws InterruptedException {
        when(repository.pollNextWorkflowInstanceIds(anyInt())).thenAnswer(waitForTickAndAnswer(2, ids(), this));
        dispatcher.run();
      }

      public void threadShutdown() {
        waitForTick(1);
        dispatcher.shutdown();
      }

      @Override
      public void finish() {
        assertPoolIsShutdown(true);
        dispatcher.shutdown();
      }
    }
    TestFramework.runOnce(new ShutdownCanBeCalledMultipleTimes());
  }

  private static  ThreadPoolTaskExecutor dispatcherPoolExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    Integer threadCount = 2 * Runtime.getRuntime().availableProcessors();
    executor.setCorePoolSize(threadCount);
    executor.setMaxPoolSize(threadCount);
    executor.setKeepAliveSeconds(0);
    executor.setAwaitTerminationSeconds(60);
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setThreadFactory(new CustomizableThreadFactory("nflow-executor-"));
    executor.afterPropertiesSet();
    return executor;
  }

  private void assertPoolIsShutdown(boolean isTrue) {
    assertEquals(pool.getThreadPoolExecutor().isShutdown(), isTrue);
  }

  private Runnable noOpRunnable() {
    return new Runnable() {
      @Override
      public void run() {
      }
    };
  }

  private Runnable waitForTickRunnable(final int tick, final MultithreadedTestCase mtc) {
    return new Runnable() {
      @Override
      public void run() {
        mtc.waitForTick(tick);
      }
    };
  }

  private WorkflowExecutor fakeWorkflowExecutor(int instanceId, final Runnable fakeCommand) {
    return new WorkflowExecutor(instanceId, null) {
      @Override
      public void run() {
        fakeCommand.run();
      }
    };
  }

  private Answer<List<Integer>> waitForTickAndAnswer(final int tick, final List<Integer> answer, final MultithreadedTestCase mtc) {
    return new Answer<List<Integer>>() {
      @Override
      public List<Integer> answer(InvocationOnMock invocation) throws Throwable {
        mtc.waitForTick(tick);
        return answer;
      }
    };
  }

  private static List<Integer> ids(Integer... ids) {
    return asList(ids);
  }
}
