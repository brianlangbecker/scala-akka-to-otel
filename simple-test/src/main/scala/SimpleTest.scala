import kamon.Kamon
import kamon.trace.Span

object SimpleTest extends App {
  println("=== Starting Simple Test ===")
  
  // Initialize Kamon
  Kamon.init()
  println("=== Kamon initialized ===")
  
  // Create a root span
  val rootSpan = Kamon.spanBuilder("simple-test-root").start()
  rootSpan.tag("test.type", "root")
  println("=== Root span created ===")

  // Create a child span
  val childSpan = Kamon.spanBuilder("simple-test-child")
    .asChildOf(rootSpan)
    .start()
  childSpan.tag("test.type", "child")
  Thread.sleep(100)
  childSpan.finish()
  println("=== Child span finished ===")

  // Create another child span
  val child2Span = Kamon.spanBuilder("simple-test-child2")
    .asChildOf(rootSpan)
    .start()
  child2Span.tag("test.type", "child2")
  Thread.sleep(100)
  child2Span.finish()
  println("=== Child span 2 finished ===")

  // Finish root span
  rootSpan.finish()
  println("=== Root span finished ===")
  
  // Wait longer for export
  println("=== Waiting for export (10 seconds) ===")
  Thread.sleep(10000)
  
  println("=== Stopping Kamon ===")
  Kamon.stop()
  
  println("=== Simple test complete ===")
}