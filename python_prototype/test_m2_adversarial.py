"""Milestone 2 Empirical Adversarial Tests:
Allergy Cross-Reactivity, Allergen Avoidance, and Non-FTS Prompt Generation.
"""

import pytest
from safety_validator import (
    SafetyValidator,
    TurnContext,
    PatientProfile,
    ToolCallRecord,
    FetchedSection,
)
from system_prompt import build_correction_prompt, build_system_prompt

validator = SafetyValidator()

def make_ctx(answer: str, profile: PatientProfile = None, tool_text: str = "") -> TurnContext:
    if not tool_text:
        tool_text = "Standard clinical guidance: acetaminophen 500 mg, azithromycin 250 mg, amoxicillin 500 mg, ibuprofen 400 mg, naproxen 500 mg, ketorolac 15 mg, Bactrim 800 mg, ciprofloxacin 500 mg."
    return TurnContext(
        tool_calls=[
            ToolCallRecord(
                tool_name="get_topic_sections_text",
                arguments={"topic_id": "test_1", "section_ids": ["s1"]},
                result=tool_text,
                success=True,
            )
        ],
        tool_results=[tool_text],
        fetched_sections=[
            FetchedSection(
                topic_id="test_1",
                topic_title="Test Topic",
                section_id="s1",
                section_title="Treatment",
                content_snippet="Standard clinical guidance.",
            )
        ],
        answer=answer,
        user_question="What should I prescribe?",
        patient_profile=profile,
    )


class TestPenicillinAllergyAdversarial:
    @pytest.mark.parametrize("drug", [
        "penicillin", "amoxicillin", "ampicillin", "piperacillin", "ticarcillin",
        "oxacillin", "nafcillin", "dicloxacillin", "augmentin", "unasyn", "zosyn",
        "amoxil", "clavulanate"
    ])
    def test_penicillin_cross_reactive_drugs_blocked(self, drug):
        profile = PatientProfile(allergies=["penicillin"])
        ctx = make_ctx(f"Prescribe {drug} 500 mg for this patient.", profile)
        res = validator.validate(ctx)
        assert not res.passed
        assert "allergy" in (res.blocked_reason or "").lower()

    @pytest.mark.parametrize("phrase", [
        "Avoid amoxicillin due to documented penicillin allergy.",
        "Amoxicillin is contraindicated in patients with penicillin allergy.",
        "Patient is allergic to penicillin; do not use ampicillin.",
        "Avoid augmentin in penicillin-allergic patients.",
        "Amoxicillin avoided; prescribe azithromycin 250 mg instead.",
        "Augmentin is not recommended due to allergy to penicillin.",
        "Hold amoxicillin and give alternative therapy.",
        "Discontinue amoxicillin immediately if started.",
        "Do not prescribe ampicillin due to penicillin allergy.",
        "Contraindicated: amoxicillin and augmentin.",
    ])
    def test_penicillin_avoidance_passes(self, phrase):
        profile = PatientProfile(allergies=["penicillin"])
        ctx = make_ctx(phrase, profile)
        res = validator.validate(ctx)
        assert res.passed, f"Avoidance phrase '{phrase}' was blocked: {res.blocked_reason}"


class TestSulfaAllergyAdversarial:
    @pytest.mark.parametrize("drug", [
        "bactrim", "septra", "sulfamethoxazole", "sulfadiazine",
        "sulfasalazine", "sulfisoxazole", "trimethoprim-sulfamethoxazole"
    ])
    @pytest.mark.parametrize("allergy", ["sulfa", "sulfonamide", "Sulfa allergy"])
    def test_sulfa_cross_reactive_drugs_blocked(self, drug, allergy):
        profile = PatientProfile(allergies=[allergy])
        ctx = make_ctx(f"First-line treatment is {drug}.", profile)
        res = validator.validate(ctx)
        assert not res.passed
        assert "allergy" in (res.blocked_reason or "").lower()

    @pytest.mark.parametrize("phrase", [
        "Avoid Bactrim due to sulfa allergy.",
        "Bactrim is contraindicated due to sulfa allergy.",
        "Septra is not recommended in sulfa-allergic patients.",
        "Sulfamethoxazole avoided; give ciprofloxacin 500 mg instead.",
    ])
    def test_sulfa_avoidance_passes(self, phrase):
        profile = PatientProfile(allergies=["sulfa"])
        ctx = make_ctx(phrase, profile)
        res = validator.validate(ctx)
        assert res.passed


class TestAspirinNsaidAllergyAdversarial:
    @pytest.mark.parametrize("drug", ["ibuprofen", "naproxen", "ketorolac"])
    @pytest.mark.parametrize("allergy", ["aspirin", "nsaid"])
    def test_nsaid_cross_reactive_drugs_blocked(self, drug, allergy):
        profile = PatientProfile(allergies=[allergy])
        ctx = make_ctx(f"Recommend {drug} for pain control.", profile)
        res = validator.validate(ctx)
        assert not res.passed
        assert "allergy" in (res.blocked_reason or "").lower()

    @pytest.mark.parametrize("phrase", [
        "Avoid ibuprofen due to aspirin allergy. Use acetaminophen 500 mg.",
        "Naproxen is contraindicated in patients with aspirin allergy.",
        "Ketorolac is not recommended.",
        "NSAIDs avoided; treat with acetaminophen 500 mg.",
    ])
    def test_nsaid_avoidance_passes(self, phrase):
        profile = PatientProfile(allergies=["aspirin"])
        ctx = make_ctx(phrase, profile)
        res = validator.validate(ctx)
        assert res.passed


class TestNonFtsPromptGeneration:
    @pytest.mark.parametrize("reason,evidence", [
        ("Answer requires section or table content retrieval.", "No section text fetched."),
        ("Clinical recommendations require database verification.", "No database tools executed."),
        ("Documented patient allergy contraindication: amoxicillin in penicillin allergy.", "Topic overview."),
        ("Documented patient renal contraindication: eGFR < 30.", "Topic overview."),
        ("Unverified clinical quantities found in response: 99.4%.", "Topic overview."),
    ])
    def test_non_fts_prompt_generation(self, reason, evidence):
        prompt = build_correction_prompt(reason, evidence)
        assert "searchTopicSections" not in prompt
        assert "search_topic_sections" not in prompt
        assert "get_topic_overview" in prompt or "getTopicOverview" in prompt
        assert "get_topic_sections_text" in prompt or "getTopicSectionsText" in prompt
