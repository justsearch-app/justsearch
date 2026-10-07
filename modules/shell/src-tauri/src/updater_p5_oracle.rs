use super::*;

#[tokio::test]
async fn p5_u2_nonempty_job_never_launches_the_installer() {
    for available in [false, true] {
        let fixture = HandoffFixture::new(available, "helpers");
        let result = fixture.run(&UpdateCoordinator::default()).await;
        assert_eq!(
            fixture.launches.load(Ordering::SeqCst),
            0,
            "installer launched while the job still held a process"
        );
        assert!(result.is_err());
        let error = result.unwrap_err();
        assert!(
            error.contains("helpers did not exit: 1 processes"),
            "{error}"
        );
        assert_eq!(fixture.launches.load(Ordering::SeqCst), 0);
        assert_eq!(fixture.intent().phase, UpgradePhase::RepairRequired);
        assert_eq!(fixture.resumes.load(Ordering::SeqCst), 1);
    }
}
