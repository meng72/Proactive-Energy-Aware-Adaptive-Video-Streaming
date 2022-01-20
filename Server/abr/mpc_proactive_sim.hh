#ifndef MPC_PROACTIVE_SIM_HH
#define MPC_PROACTIVE_SIM_HH

#include "abr_algo.hh"

#include <deque>

class MPCProactiveSim : public ABRAlgo
{
public:
  MPCProactiveSim(const WebSocketClient & client,
      const std::string & abr_name, const YAML::Node & abr_config);

  void video_chunk_acked(Chunk && c) override;
  VideoFormat select_video_format() override;

protected:
  static constexpr size_t MAX_NUM_PAST_CHUNKS = 8;
  static constexpr size_t MAX_LOOKAHEAD_HORIZON = 5;
  static constexpr size_t MAX_DIS_BUF_LENGTH = 100;
  static constexpr double REBUFFER_LENGTH_COEFF = 20;
  static constexpr double SSIM_DIFF_COEFF = 1;
  static constexpr size_t MAX_NUM_FORMATS = 20;
  static constexpr double HIGH_SENDING_TIME = 10000;
  static constexpr size_t MAX_DIS_ENERGY_LENGTH = 100;
  static constexpr double MIN_PER_ENERGY = -1000.0;
  static constexpr double MAX_PER_ENERGY = 1000.0;
  static constexpr double MAX_POWER_BUDGET = 5000;

  /* past chunks and max number of them */
  struct ChunkInfo {
    double ssim;          /* chunk ssim */
    unsigned int size;    /* chunk size */
    uint64_t trans_time;  /* transmission time */
    double pred_err;      /* throughput prediction error */
  };
  size_t max_num_past_chunks_ {MAX_NUM_PAST_CHUNKS};
  std::deque<ChunkInfo> past_chunks_ {};

  int enable_output_debug_ = 0;
  std::string algo_name_ = "MPCProactiveSim";
  std::string log_dir_ = {};

  /* all the time durations are measured in sec */
  size_t max_lookahead_horizon_ {MAX_LOOKAHEAD_HORIZON};
  size_t max_power_lookahead_horizon_ {MAX_LOOKAHEAD_HORIZON};
  size_t lookahead_horizon_ {};
  size_t power_lookahead_horizon_ {};
  double chunk_length_ {};
  size_t dis_buf_length_ {MAX_DIS_BUF_LENGTH};
  double unit_buf_length_ {};
  size_t num_formats_ {};
  double rebuffer_length_coeff_ {REBUFFER_LENGTH_COEFF};
  double ssim_diff_coeff_ {SSIM_DIFF_COEFF};
  int video_streaming_mode_ = 1;

  /* for robust mpc */
  bool is_robust_ {false};
  double last_tp_pred_ {-1};

  /* whether the current chunk is the first chunk */
  bool is_init_ {};

  /* for the current buffer length */
  size_t curr_buffer_ {};

  /* for storing the value function */
  uint64_t flag_[MAX_LOOKAHEAD_HORIZON + 1][MAX_DIS_BUF_LENGTH + 1][MAX_NUM_FORMATS][MAX_DIS_ENERGY_LENGTH] {};
  double v_[MAX_LOOKAHEAD_HORIZON + 1][MAX_DIS_BUF_LENGTH + 1][MAX_NUM_FORMATS][MAX_DIS_ENERGY_LENGTH] {};

  /* record the current round of DP */
  uint64_t curr_round_ {};

  /* map the discretized buffer length to the estimation */
  double real_buffer_[MAX_DIS_BUF_LENGTH + 1] {};

  /* unit sending time estimation */
  double unit_sending_time_[MAX_LOOKAHEAD_HORIZON + 1 + MAX_NUM_PAST_CHUNKS] {};

  /* the ssim of the chunk given the timestamp and format */
  double curr_ssims_[MAX_LOOKAHEAD_HORIZON + 1][MAX_NUM_FORMATS] {};
  std::vector<VideoFormat> vformats_ {};

  /* the estimation of sending time given the timestamp and format */
  double curr_sending_time_[MAX_LOOKAHEAD_HORIZON + 1][MAX_NUM_FORMATS] {};

  double buffer_threshold_ {7.0}; // 7s 

  // Power model for network
  double net_model_coeff_ {0.0018}; 
  double net_model_intercept_ {152.56}; 
  double net_model_tail_ {123.27}; 
  double net_model_idle_ {0}; 

  double net_power_[MAX_LOOKAHEAD_HORIZON + 1][MAX_NUM_FORMATS] {};
  double unit_energy_ {(MAX_PER_ENERGY - MIN_PER_ENERGY) / MAX_DIS_ENERGY_LENGTH}; 
  double video_timeline_ = {};
  double duration_prev_ack_ = {};

  double max_power_budget_ {MAX_POWER_BUDGET};
  double orig_power_budget_ {1000};
  double energy_deposit_ {0};
  size_t prev_selected_fmt_ {0};

  double power_history_ {0};
  double time_history_ {0};
  double prev_cum_rebuffer_ {0};
  std::vector<size_t> selected_formats {};
  std::vector<size_t> lookahead_formats {};

  void reinit();

  /* calculate the value of corresponding state and return the best strategy */
  size_t update_value(size_t i, size_t curr_buffer, size_t curr_format,
    size_t curr_energy);

  /* return the qvalue of the given cur state and next action */
  double get_qvalue(size_t i, size_t curr_buffer, size_t curr_format,
    size_t next_format, size_t curr_energy);

  /* return the value of the given state */
  double get_value(size_t i, size_t curr_buffer, size_t curr_format,
    size_t curr_energy);

  /* discretize the buffer length */
  size_t discretize_buffer(double buf);
  size_t discretize_energy(double energy);

  void update_history(Chunk c);

  double compute_net_power(int vsize, double trans_time, double active_ratio);
  double compute_video_power(double base_time, double offset_time);
  double get_video_power_based_on_vwdith(int vwidth);
  size_t min_power_format();

  void append_to_abr_log(const std::string & log_stem, const std::string & log_line);
};

#endif /* MPC_PROACTIVE_SIM_HH */
